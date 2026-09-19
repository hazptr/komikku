package eu.kanade.tachiyomi.data.download

import kotlinx.serialization.Serializable

const val BGM_INFO_FILE = "bgm.json"
const val BGM_FILE_PREFIX = "bgm_"

/**
 * Background music of a downloaded chapter, written beside its images.
 *
 * Cues are keyed by image file name, not page index: an index drifts when a tall page is split
 * into several files, and the offline loaders do not all sort the same way.
 */
@Serializable
data class BgmInfo(
    val version: Int = VERSION,
    /** Track id to the file holding it, named after a hash of the id since ids come from the source. */
    val files: Map<String, String> = emptyMap(),
    /** Image file name to the id of the track that plays over it. */
    val cues: Map<String, String> = emptyMap(),
) {
    companion object {
        const val VERSION = 1
    }
}
