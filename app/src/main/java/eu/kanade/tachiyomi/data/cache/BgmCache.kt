package eu.kanade.tachiyomi.data.cache

import android.content.Context
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.AudioTrack
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.InputStream

private const val PARAMETER_CACHE_DIRECTORY = "bgm_disk_cache"
private const val PARAMETER_APP_VERSION = 1
private const val PARAMETER_VALUE_COUNT = 1
private const val PARAMETER_CACHE_SIZE = 64L * 1024 * 1024

/** Tracks are seconds of music, not media files. Anything larger is a broken or hostile origin. */
private const val MAX_TRACK_BYTES = 16L * 1024 * 1024

/**
 * On-disk store for source-provided audio tracks.
 *
 * Bounded and LRU-evicted, unlike a bare file per track in the cache directory: tracks are around
 * a megabyte each and nothing else would ever delete them. Keys are hashed rather than used
 * verbatim because track ids come from the source and are not safe to put in a path.
 */
class BgmCache(private val context: Context) {

    private val diskCache = DiskLruCache.open(
        File(context.cacheDir, PARAMETER_CACHE_DIRECTORY),
        PARAMETER_APP_VERSION,
        PARAMETER_VALUE_COUNT,
        PARAMETER_CACHE_SIZE,
    )

    /** Serialises writers so a track requested from several pages at once is fetched once. */
    private val lock = Mutex()

    private fun keyOf(trackId: String) = DiskUtil.hashKeyForDisk(trackId)

    /** The stored track, or null if it has not been fetched yet. */
    fun find(trackId: String): File? =
        File(diskCache.directory, keyOf(trackId) + ".0").takeIf { it.isFile && it.length() > 0 }

    /**
     * Ensures [track] is stored, fetching it through the source's client if needed.
     *
     * Returns null rather than throwing: audio is never worth failing a page load over.
     */
    suspend fun fetch(track: AudioTrack, source: HttpSource?): File? = withIOContext {
        find(track.id)?.let { return@withIOContext it }
        lock.withLock {
            find(track.id) ?: runCatching {
                val client = requireNotNull(source) { "remote track needs a source" }
                client.client.newCall(GET(track.url, client.headers)).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body
                    check(body.contentLength() <= MAX_TRACK_BYTES) { "track too large" }
                    write(track.id) { out -> body.source().saveTo(out) }
                }
                find(track.id)
            }.onFailure {
                if (it is CancellationException) throw it
                logcat(LogPriority.WARN, it) { "Failed to fetch audio track ${track.id}" }
            }.getOrNull()
        }
    }

    /** Stores bytes for a track that came from somewhere other than the network, e.g. a download. */
    fun put(trackId: String, input: InputStream): File? = runCatching {
        write(trackId) { out -> input.use { it.copyTo(out) } }
        find(trackId)
    }.onFailure {
        logcat(LogPriority.WARN, it) { "Failed to store audio track $trackId" }
    }.getOrNull()

    private inline fun write(trackId: String, body: (java.io.OutputStream) -> Unit) {
        val editor = diskCache.edit(keyOf(trackId)) ?: return
        try {
            editor.newOutputStream(0).use(body)
            diskCache.flush()
            editor.commit()
        } finally {
            editor.abortUnlessCommitted()
        }
    }
}
