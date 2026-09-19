package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.data.cache.BgmCache
import eu.kanade.tachiyomi.source.model.AudioTrack
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private const val FADE_IN_MS = 900L
private const val FADE_OUT_MS = 600L
private const val FADE_STEPS = 24

/**
 * [MediaPlayer] output for source-provided background music: audio focus, the noisy receiver and
 * crossfades. Cue decisions - settle debounce, supersede, enable/disable, loop changes - live in
 * [BgmCueMachine]; this class only carries out what it decides.
 *
 * Swaps crossfade because the outgoing player is detached from [player] before its fade-out
 * starts. Fetching and caching are [BgmCache]'s job.
 */
class ChapterBgmPlayer(private val scope: CoroutineScope, private val bgmCache: BgmCache) : BgmCueMachine.Output {

    private val context = Injekt.get<Application>()
    private val audioManager = context.getSystemService<AudioManager>()

    private val cueMachine = BgmCueMachine(scope, this)

    private var player: MediaPlayer? = null

    /** Intent to be paused, independent of whether a player currently exists or is playing. */
    private var pausedByLifecycle = false

    private var receiverRegistered = false

    /** Mirrors the user's preference, read live by the completion listener below. */
    private var loop = true

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(mediaAttributes())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
                    AudioManager.AUDIOFOCUS_GAIN -> if (!pausedByLifecycle) player?.start()
                }
            }
            .build()
    }

    private fun mediaAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun onPageChanged(track: AudioTrack?, source: HttpSource?, enabled: Boolean, loop: Boolean) =
        cueMachine.onPageChanged(track, source, enabled, loop)

    override suspend fun load(track: AudioTrack, source: HttpSource?): File? {
        val file = bgmCache.fetch(track, source)
        if (file == null) logcat(LogPriority.WARN) { "[BGM] fetch failed for ${track.id}" }
        return file
    }

    override suspend fun start(file: File, id: String, loop: Boolean): Boolean = withUIContext {
        this@ChapterBgmPlayer.loop = loop
        if (!requestFocus()) {
            logcat(LogPriority.WARN) { "[BGM] audio focus denied for $id" }
            return@withUIContext false
        }
        registerNoisyReceiver()
        player = MediaPlayer().apply {
            setAudioAttributes(mediaAttributes())
            setDataSource(file.path)
            isLooping = loop
            setVolume(0f, 0f)
            setOnErrorListener { _, what, extra ->
                logcat(LogPriority.WARN) { "[BGM] MediaPlayer error $what/$extra" }
                stop()
                true
            }
            setOnCompletionListener { finished ->
                // A track that has run out otherwise holds a native player and audio focus for
                // the rest of its cue, which can be most of a scene. Clearing activeId also lets
                // it play again if the reader leaves the cue and comes back; desiredId is
                // untouched, so staying put does not restart it.
                if (!this@ChapterBgmPlayer.loop && player === finished) {
                    player = null
                    cueMachine.onPlaybackEnded(id)
                    release(finished)
                    unregisterNoisyReceiver()
                    abandonFocus()
                }
            }
            setOnPreparedListener { mp ->
                // Guards a callback that arrives after this player was superseded or released,
                // and one that arrives while the reader is backgrounded.
                if (player !== mp || pausedByLifecycle) return@setOnPreparedListener
                mp.start()
                // Off the main thread: a fade is 24 steps over ~1s and would otherwise contend
                // with frame rendering at exactly the moment a track starts, i.e. mid-scroll.
                scope.launchIO { fade(mp, from = 0f, to = 1f, millis = FADE_IN_MS) }
            }
            prepareAsync()
        }
        true
    }

    override fun setLooping(loop: Boolean) {
        this.loop = loop
        player?.isLooping = loop
    }

    /**
     * Fades the current track out and releases it. Detaches [player] first so a track starting
     * during the fade crossfades with it instead of the two fighting over one reference.
     */
    override fun fadeOutAndRelease() {
        val mp = player ?: return
        player = null
        unregisterNoisyReceiver()
        scope.launchIO {
            try {
                fade(mp, from = 1f, to = 0f, millis = FADE_OUT_MS)
            } finally {
                release(mp)
                // Only give up focus if nothing else claimed it while fading out.
                if (player == null) abandonFocus()
            }
        }
    }

    /**
     * Ramps [mp] between two volumes. Cancelling the caller simply abandons the ramp, which is why
     * a fade holds its own player reference rather than reading [player].
     */
    private suspend fun fade(mp: MediaPlayer, from: Float, to: Float, millis: Long) {
        val stepDelay = millis / FADE_STEPS
        for (i in 1..FADE_STEPS) {
            val v = from + (to - from) * (i / FADE_STEPS.toFloat())
            if (runCatching { mp.setVolume(v, v) }.isFailure) return
            delay(stepDelay)
        }
    }

    fun pause() {
        pausedByLifecycle = true
        player?.takeIf { it.isPlaying }?.pause()
    }

    fun resume() {
        if (!pausedByLifecycle) return
        pausedByLifecycle = false
        if (requestFocus()) player?.start()
    }

    /** Immediate teardown, for audio-focus loss and reader exit. No fade: the scope may be dying. */
    fun stop() {
        cueMachine.reset()
        player?.let(::release)
        player = null
        pausedByLifecycle = false
        unregisterNoisyReceiver()
        abandonFocus()
    }

    private fun release(mp: MediaPlayer) {
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.reset() }
        runCatching { mp.release() }
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        return manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private fun registerNoisyReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!receiverRegistered) return
        context.unregisterReceiver(noisyReceiver)
        receiverRegistered = false
    }
}
