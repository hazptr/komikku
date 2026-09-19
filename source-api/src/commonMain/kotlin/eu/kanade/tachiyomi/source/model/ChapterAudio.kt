package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable

/**
 * Audio a source provides for a chapter, as a timeline over its pages. Attach it to any one page;
 * the reader takes it from the first page carrying it.
 */
@Serializable
data class ChapterAudio(
    val tracks: List<AudioTrack> = emptyList(),
    val cues: List<AudioCue> = emptyList(),
) {
    /** The track playing over [pageIndex], or null for silence. Overlaps resolve by priority. */
    fun trackAt(pageIndex: Int): AudioTrack? {
        val cue = cues
            .filter { pageIndex >= it.fromPageIndex && pageIndex < it.toPageIndex }
            .maxByOrNull { it.priority }
            ?: return null
        return tracks.firstOrNull { it.id == cue.trackId }
    }
}

@Serializable
data class AudioTrack(
    /** Unique within the source. */
    val id: String,
    /** Fetched through the source's client, so expiring media can be resolved in an interceptor. */
    val url: String,
)

/** A half-open page range over which [trackId] plays. */
@Serializable
data class AudioCue(
    val trackId: String,
    val fromPageIndex: Int,
    val toPageIndex: Int,
    val priority: Int = 0,
)
