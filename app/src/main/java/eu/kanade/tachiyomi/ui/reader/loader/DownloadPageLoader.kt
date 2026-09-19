package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.BgmCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.BGM_INFO_FILE
import eu.kanade.tachiyomi.data.download.BgmInfo
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.AudioCue
import eu.kanade.tachiyomi.source.model.AudioTrack
import eu.kanade.tachiyomi.source.model.ChapterAudio
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.injectLazy
import java.io.InputStream

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) : PageLoader() {

    private val context: Application by injectLazy()
    private val json: Json by injectLazy()
    private val bgmCache: BgmCache by injectLazy()
    private val readerPreferences: ReaderPreferences by injectLazy()

    private var archivePageLoader: ArchivePageLoader? = null

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        val chapterPath = downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            // SY -->
            manga.ogTitle,
            // SY <--
            source,
        )
        val pages = if (chapterPath?.isFile == true) {
            getPagesFromArchive(chapterPath)
        } else {
            getPagesFromDirectory()
        }
        return attachBgm(pages, chapterPath)
    }

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val loader = ArchivePageLoader(file.archiveReader(context)).also { archivePageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl) {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.Ready
                // Taken from the page's own uri rather than by re-listing the directory: relying
                // on buildPageList's filter and sort to line up by position would break silently
                // if either ever changed. Document uris end in the file name either way.
                name = page.uri?.lastPathSegment?.substringAfterLast('/')
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }

    /**
     * Re-attaches the background music a [BgmInfo] sidecar recorded for this chapter.
     *
     * Almost no chapters carry a sidecar, so a miss here must be silent and cheap. Past that
     * point, a warning is worth it: a sidecar that won't parse or a track file that has gone
     * missing means a chapter that should have music will just play silently instead, with
     * nothing in the UI to say why.
     */
    private fun attachBgm(pages: List<ReaderPage>, chapterPath: UniFile?): List<ReaderPage> {
        // Checked first, before any archive or network I/O: a user with audio off should never
        // pay for a sidecar read or a track fetch just for opening a chapter.
        if (!readerPreferences.bgmEnabled().get()) return pages
        chapterPath ?: return pages
        val isArchive = chapterPath.isFile
        val info = readBgmInfo(chapterPath, isArchive) ?: return pages
        if (info.cues.isEmpty()) return pages

        // Resolve each cued track once rather than once per page.
        val cuedTrackIds = info.cues.values.toSet()
        val tracks = cuedTrackIds.mapNotNull { trackId ->
            val filename = info.files[trackId] ?: return@mapNotNull null
            val url = if (isArchive) {
                extractTrackFromArchive(chapterPath, filename, trackId)
            } else {
                findTrackInDirectory(chapterPath, filename)
            } ?: return@mapNotNull null
            AudioTrack(trackId, url)
        }
        if (tracks.isEmpty()) return pages
        val resolvedIds = tracks.map { it.id }.toSet()

        // Run-length encode the per-file cues into ranges over the reader's own page index,
        // which is what ChapterAudio.trackAt is keyed on.
        val cues = mutableListOf<AudioCue>()
        var i = 0
        while (i < pages.size) {
            val trackId = info.cues[pages[i].name]?.takeIf { it in resolvedIds }
            if (trackId == null) {
                i++
                continue
            }
            var j = i + 1
            while (j < pages.size && info.cues[pages[j].name] == trackId) j++
            cues += AudioCue(trackId, i, j)
            i = j
        }
        if (cues.isEmpty()) return pages

        // Attached to a single page; the reader reads it back with firstNotNullOfOrNull.
        pages.firstOrNull()?.chapterAudio = ChapterAudio(tracks, cues)
        return pages
    }

    private fun readBgmInfo(chapterPath: UniFile, isArchive: Boolean): BgmInfo? {
        val raw = try {
            if (isArchive) {
                chapterPath.archiveReader(context).use { reader ->
                    reader.getEntryStream(BGM_INFO_FILE)?.use { it.bufferedReader().readText() }
                }
            } else {
                chapterPath.findFile(BGM_INFO_FILE)?.openInputStream()?.use { it.bufferedReader().readText() }
            } ?: return null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[BGM] failed to read $BGM_INFO_FILE" }
            return null
        }

        val info = try {
            json.decodeFromString<BgmInfo>(raw)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[BGM] malformed $BGM_INFO_FILE" }
            return null
        }

        // A newer sidecar is a format this build would read wrong rather than not at all.
        if (info.version > BgmInfo.VERSION) {
            logcat(LogPriority.WARN) {
                "[BGM] sidecar version ${info.version} is newer than supported (${BgmInfo.VERSION})"
            }
            return null
        }
        return info
    }

    /** Directory case: the track is already a plain file, so the player can open it by uri. */
    private fun findTrackInDirectory(chapterPath: UniFile, filename: String): String? {
        val file = chapterPath.findFile(filename)
        if (file == null) {
            logcat(LogPriority.WARN) { "[BGM] track file $filename missing from $chapterPath" }
            return null
        }
        return file.uri.toString()
    }

    /**
     * CBZ case: an archive entry has no file uri of its own, so pull its bytes out once into
     * [BgmCache], the same store a remote track would be fetched into. That makes this a cache
     * hit for the player instead of a second copy living outside it.
     */
    private fun extractTrackFromArchive(chapterFile: UniFile, filename: String, trackId: String): String? {
        bgmCache.find(trackId)?.let { return Uri.fromFile(it).toString() }

        logcat { "[BGM] extracting $filename from the downloaded archive" }
        val cached = try {
            chapterFile.archiveReader(context).use { reader ->
                val input = reader.getEntryStream(filename) ?: error("track entry $filename missing from archive")
                bgmCache.put(trackId, input)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[BGM] failed to extract $filename from archive" }
            null
        } ?: return null
        return Uri.fromFile(cached).toString()
    }
}

/**
 * [ArchiveReader.getInputStream] matches on the full entry name, which misses whenever the cbz
 * wraps its pages in a top-level folder. Widen to a basename match on that miss.
 */
private fun ArchiveReader.getEntryStream(name: String): InputStream? {
    getInputStream(name)?.let { return it }
    val fallback = useEntries { entries ->
        entries.firstOrNull { it.isFile && it.name.substringAfterLast('/') == name }
    }
    return fallback?.let { getInputStream(it.name) }
}
