package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.AudioTrack
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

private const val SETTLE_MS = 250L

class BgmCueMachineTest {

    private val trackA = AudioTrack("a", "https://example.test/a")
    private val trackB = AudioTrack("b", "https://example.test/b")

    @Test
    fun `a cue that stays current for the settle window commits exactly once`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)

        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)

        output.startCalls shouldBe listOf("a")
    }

    @Test
    fun `a cue superseded before the settle window never plays at all`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)

        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS / 2)
        machine.onPageChanged(trackB, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)

        output.loadCalls shouldBe listOf("b")
        output.startCalls shouldBe listOf("b")
    }

    @Test
    fun `scrolling across five cues quickly commits only the last`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)
        val tracks = listOf("a", "b", "c", "d", "e").map { AudioTrack(it, "https://example.test/$it") }

        tracks.forEach { track ->
            machine.onPageChanged(track, source = null, enabled = true, loop = true)
            advanceTimeBy(SETTLE_MS / 2)
        }
        advanceTimeBy(SETTLE_MS)

        output.startCalls shouldBe listOf("e")
    }

    @Test
    fun `a cue that leaves and returns while its load is in flight can still play afterwards`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)
        val pendingLoad = CompletableDeferred<File?>()
        output.pendingLoads["a"] = pendingLoad

        // Cue A settles and starts fetching, but the fetch never resolves on its own.
        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)
        runCurrent()
        output.loadCalls shouldBe listOf("a")

        // Scrolling away cancels the in-flight commit before its fetch ever completes.
        machine.onPageChanged(trackB, source = null, enabled = true, loop = true)
        runCurrent()

        // Scrolling straight back to A must not be blocked by a leftover activeId.
        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)
        runCurrent()
        output.loadCalls shouldBe listOf("a", "a")

        pendingLoad.complete(File("/a"))
        advanceUntilIdle()

        output.startCalls shouldBe listOf("a")
    }

    @Test
    fun `toggling enabled=false stops immediately without waiting for the settle`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)

        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)
        val fadeOutsBeforeDisable = output.fadeOutCalls

        machine.onPageChanged(trackA, source = null, enabled = false, loop = true)

        output.fadeOutCalls shouldBe fadeOutsBeforeDisable + 1
    }

    @Test
    fun `toggling enabled back on re-commits the current cue`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)

        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)
        machine.onPageChanged(trackA, source = null, enabled = false, loop = true)

        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)

        output.startCalls shouldBe listOf("a", "a")
    }

    @Test
    fun `the same cue reported repeatedly does not restart playback`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)

        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)
        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)

        output.startCalls shouldBe listOf("a")
    }

    @Test
    fun `a null cue stops`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)

        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)
        val fadeOutsBeforeNull = output.fadeOutCalls

        machine.onPageChanged(null, source = null, enabled = true, loop = true)
        advanceTimeBy(SETTLE_MS + 1)

        output.fadeOutCalls shouldBe fadeOutsBeforeNull + 1
    }

    @Test
    fun `loop preference is forwarded only when it actually changes`() = runTest {
        val output = FakeOutput()
        val machine = BgmCueMachine(this, output)

        // Machine starts with loop = true, matching the default preference.
        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        machine.onPageChanged(trackA, source = null, enabled = true, loop = true)
        machine.onPageChanged(trackA, source = null, enabled = true, loop = false)
        machine.onPageChanged(trackA, source = null, enabled = true, loop = false)

        output.loopCalls shouldBe listOf(false)
    }

    /** Records every command the machine emits; fetches resolve immediately unless queued. */
    private class FakeOutput : BgmCueMachine.Output {

        val loadCalls = mutableListOf<String>()
        val startCalls = mutableListOf<String>()
        val loopCalls = mutableListOf<Boolean>()
        val pendingLoads = mutableMapOf<String, CompletableDeferred<File?>>()
        var fadeOutCalls = 0
        var startResult = true

        override suspend fun load(track: AudioTrack, source: HttpSource?): File? {
            loadCalls += track.id
            return pendingLoads[track.id]?.await() ?: File("/${track.id}")
        }

        override suspend fun start(file: File, id: String, loop: Boolean): Boolean {
            startCalls += id
            return startResult
        }

        override fun setLooping(loop: Boolean) {
            loopCalls += loop
        }

        override fun fadeOutAndRelease() {
            fadeOutCalls++
        }
    }
}
