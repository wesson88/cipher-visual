package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenderSlotTest {
    private fun block() = IntArrayPixels(40, 12, IntArray(480) { 0xFF112233.toInt() })
    private fun req(mode: CancelMode = CancelMode.TEARDOWN) =
        PlayRequest(IrTemplates.dissolve(1000.0), block(), block(), slot = "v", preemptMode = mode)

    private class Fixture {
        val released = ArrayList<String>()
        val slot = RenderSlot<String>(onRelease = { released += it }, onChanged = {})
    }

    @Test
    fun teardownHandoffSwitchesImmediately() {
        val f = Fixture()
        val engine = VisualEngine(ManualClock())
        val a = (engine.play(req()) as PlayResult.Started).handle
        f.slot.attach(a, "A")
        val b = (engine.play(req()) as PlayResult.Started).handle // a 同步 teardown → 已释放
        f.slot.attach(b, "B")
        assertEquals(listOf("A"), f.released)
        assertEquals(b, f.slot.currentHandle)
    }

    @Test
    fun reverseHandoffKeepsRenderingPreviousUntilItEnds() {
        val f = Fixture()
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val a = (engine.play(req()) as PlayResult.Started).handle
        f.slot.attach(a, "A")
        clock.run(0.0, 300.0)
        val b = (engine.play(req(CancelMode.REVERSE)) as PlayResult.Queued).handle
        f.slot.attach(b, "B")
        assertEquals(a, f.slot.currentHandle, "前一次倒放期间继续画它")
        assertEquals(b, f.slot.latestHandle)
        clock.run(320.0, 1000.0)
        assertEquals(listOf("A"), f.released)
        assertEquals(b, f.slot.currentHandle, "前一次终态后切到新句柄")
    }

    @Test
    fun replacedPendingIsReleasedExactlyOnce() {
        val f = Fixture()
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val a = (engine.play(req()) as PlayResult.Started).handle
        f.slot.attach(a, "A")
        clock.run(0.0, 300.0)
        f.slot.attach((engine.play(req(CancelMode.REVERSE)) as PlayResult.Queued).handle, "B")
        f.slot.attach((engine.play(req(CancelMode.REVERSE)) as PlayResult.Queued).handle, "C")
        assertEquals(listOf("B"), f.released)
        clock.run(320.0, 1000.0)
        assertEquals(listOf("B", "A"), f.released)
        f.slot.detach()
        assertEquals(listOf("B", "A", "C"), f.released)
        assertNull(f.slot.currentHandle)
    }
}
