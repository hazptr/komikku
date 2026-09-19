package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.AudioTrack
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * How long a cue must stay current before it is acted on.
 *
 * Page images cancel instead of debouncing, which works because a cancelled image was never
 * drawn. Starting playback cannot be undone the same way, so flinging past a short cue must
 * not sound it at all. Fetching itself is [BgmCueMachine.Output.load]'s job.
 */
private const val SETTLE_MS = 250L

/**
 * Cue decision logic for source-provided background music: settle debounce, supersede,
 * enable/disable and loop changes. Has no Android dependency, so it can be driven directly from a
 * plain JVM test; [output] does the actual fetching and playback.
 */
class BgmCueMachine(private val scope: CoroutineScope, private val output: Output) {

    /** What this machine decides should happen, carried out by whoever owns the real player. */
    interface Output {
        /** Fetches (and caches) the audio for [track]. Null means it could not be obtained. */
        suspend fun load(track: AudioTrack, source: HttpSource?): File?

        /** Hands [file] to the real player. Returns whether it actually started. */
        suspend fun start(file: File, id: String, loop: Boolean): Boolean

        /** Applies a loop-preference change to whatever is currently playing. */
        fun setLooping(loop: Boolean)

        /** Fades out and releases whatever is currently playing, if anything. */
        fun fadeOutAndRelease()
    }

    /** What should be playing once the current cue settles. Compared against to debounce. */
    private var desiredId: String? = null

    /** What is actually loading or playing right now. */
    private var activeId: String? = null

    private var settleJob: Job? = null

    /** Mirrors the user's preference; see [onPageChanged]. */
    private var loop = true

    fun onPageChanged(track: AudioTrack?, source: HttpSource?, enabled: Boolean, loop: Boolean) {
        if (loop != this.loop) {
            this.loop = loop
            output.setLooping(loop)
        }

        // Turning the preference off is a deliberate action, not a scroll artifact, so it skips
        // the settle delay. Still fades rather than cutting: an abrupt stop is startling.
        if (!enabled) {
            if (desiredId != null || activeId != null) {
                settleJob?.cancel()
                desiredId = null
                activeId = null
                output.fadeOutAndRelease()
            }
            return
        }

        if (track?.id == desiredId) return

        desiredId = track?.id
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_MS)
            if (desiredId != track?.id) return@launch
            if (track == null) {
                activeId = null
                output.fadeOutAndRelease()
            } else {
                commit(track, source)
            }
        }
    }

    private suspend fun commit(track: AudioTrack, source: HttpSource?) {
        if (activeId == track.id) return
        output.fadeOutAndRelease()
        activeId = track.id
        // Tracks whether this call actually handed the track off to the player, so every other
        // exit - cancellation, being superseded, start() failing - clears activeId instead of
        // leaving it pointed at a track that will never play and can never be retried.
        var started = false
        try {
            val file = output.load(track, source) ?: return
            if (desiredId != track.id) return
            started = output.start(file, track.id, loop)
        } finally {
            if (!started && activeId == track.id) activeId = null
        }
    }

    /** Immediate teardown of decision state, for audio-focus loss and reader exit. */
    fun reset() {
        settleJob?.cancel()
        settleJob = null
        desiredId = null
        activeId = null
    }

    /** The active track finished on its own (not looping); lets it be retried if revisited. */
    fun onPlaybackEnded(id: String) {
        if (activeId == id) activeId = null
    }
}
