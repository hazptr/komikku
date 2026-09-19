package eu.kanade.tachiyomi.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterAudioTest {

    private val quiet = AudioTrack("quiet", "https://example.test/quiet")
    private val loud = AudioTrack("loud", "https://example.test/loud")

    private fun audio(vararg cues: AudioCue) = ChapterAudio(listOf(quiet, loud), cues.toList())

    @Test
    fun `resolves a track inside its range`() {
        val audio = audio(AudioCue("quiet", fromPageIndex = 2, toPageIndex = 5))

        audio.trackAt(2) shouldBe quiet
        audio.trackAt(4) shouldBe quiet
    }

    @Test
    fun `range is half-open`() {
        val audio = audio(AudioCue("quiet", fromPageIndex = 2, toPageIndex = 5))

        audio.trackAt(1) shouldBe null
        audio.trackAt(5) shouldBe null
    }

    @Test
    fun `gaps between cues are silent`() {
        val audio = audio(
            AudioCue("quiet", fromPageIndex = 0, toPageIndex = 3),
            AudioCue("loud", fromPageIndex = 6, toPageIndex = 9),
        )

        audio.trackAt(4) shouldBe null
    }

    @Test
    fun `overlapping cues resolve by priority`() {
        val audio = audio(
            AudioCue("quiet", fromPageIndex = 0, toPageIndex = 10),
            AudioCue("loud", fromPageIndex = 4, toPageIndex = 6, priority = 1),
        )

        audio.trackAt(3) shouldBe quiet
        audio.trackAt(5) shouldBe loud
        audio.trackAt(6) shouldBe quiet
    }

    @Test
    fun `cue naming an unknown track resolves to silence`() {
        val audio = ChapterAudio(
            tracks = listOf(quiet),
            cues = listOf(AudioCue("missing", fromPageIndex = 0, toPageIndex = 4)),
        )

        audio.trackAt(1) shouldBe null
    }

    @Test
    fun `no cues means silence`() {
        ChapterAudio().trackAt(0) shouldBe null
    }
}
