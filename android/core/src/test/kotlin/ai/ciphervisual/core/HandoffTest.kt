package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 取消交接：「最新来的主动取消前一次正在执行的，前一次取消拿到回调后再发起最新的」。
 * 覆盖同渲染位（slot）与 dropOldest 挤占两条路径，以及单请求超总预算直接拒绝。
 */
class HandoffTest {
    private val slot = "view-1"

    /** 共享一条事件流，断言跨句柄的回调先后。 */
    private class Log : VisualListener {
        val events = ArrayList<String>()
        override fun onStateChanged(handle: VisualHandle, state: HandleState) {
            events += "h${handle.id}:${state.wire}" + (handle.endReason?.let { "/$it" } ?: "")
        }
    }

    private fun block(w: Int) = IntArrayPixels(w, 12, IntArray(w * 12) { 0xFF112233.toInt() })

    private fun req(log: Log, mode: CancelMode = CancelMode.TEARDOWN, slot: Any? = this.slot, w: Int = 40) =
        PlayRequest(IrTemplates.dissolve(1000.0), block(w), block(w), log, slot = slot, preemptMode = mode)

    @Test
    fun teardownPreemptOnSameSlotStartsNewAfterPreviousCallback() {
        val log = Log()
        val engine = VisualEngine(ManualClock())
        val a = (engine.play(req(log)) as PlayResult.Started).handle
        val b = (engine.play(req(log)) as PlayResult.Started).handle
        assertEquals(EndReason.PREEMPTED, a.endReason)
        assertEquals(listOf("h1:active", "h1:completed/PREEMPTED", "h2:active"), log.events, "前一次终态回调在前，新句柄启动在后")
        assertEquals(HandleState.ACTIVE, b.state)
    }

    @Test
    fun reversePreemptWaitsUntilPreviousReverseCompletes() {
        val log = Log()
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val a = (engine.play(req(log)) as PlayResult.Started).handle
        clock.run(0.0, 800.0)
        val b = (engine.play(req(log, CancelMode.REVERSE)) as PlayResult.Queued).handle
        assertEquals(HandleState.CANCELLING, a.state)
        assertEquals(HandleState.IDLE, b.state)
        assertTrue(b.isAwaitingHandoff)
        clock.run(820.0, 1600.0)
        assertEquals(EndReason.PREEMPTED, a.endReason, "被取代的句柄 reverse 完成后终态原因是 PREEMPTED")
        assertEquals(HandleState.ACTIVE, b.state)
        val i = log.events.indexOf("h1:completed/PREEMPTED")
        assertTrue(i >= 0 && log.events.indexOf("h2:active") == i + 1, "前一次回调之后才启动新的：${log.events}")
    }

    @Test
    fun newerRequestReplacesPendingOneAndStillWaitsForTheReversingOne() {
        val log = Log()
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val a = (engine.play(req(log)) as PlayResult.Started).handle
        clock.run(0.0, 300.0)
        val b = (engine.play(req(log, CancelMode.REVERSE)) as PlayResult.Queued).handle
        val c = (engine.play(req(log, CancelMode.REVERSE)) as PlayResult.Queued).handle
        assertEquals(EndReason.PREEMPTED, b.endReason, "没启动过的前一次直接撤回")
        assertEquals(HandleState.CANCELLING, a.state)
        assertEquals(HandleState.IDLE, c.state)
        clock.run(320.0, 1000.0)
        assertEquals(HandleState.COMPLETED, a.state)
        assertEquals(HandleState.ACTIVE, c.state)
        assertTrue("h2:active" !in log.events, "被取代的等待者不应闪现启动")
    }

    @Test
    fun withdrawingThePendingOneDoesNotForgetTheReversingOne() {
        // 回归：曾只记「最新一个」，撤回等待者后渲染位遗忘了仍在倒放的前任
        val log = Log()
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val a = (engine.play(req(log)) as PlayResult.Started).handle
        clock.run(0.0, 300.0)
        val b = (engine.play(req(log, CancelMode.REVERSE)) as PlayResult.Queued).handle
        b.cancel(CancelMode.TEARDOWN)
        assertEquals(EndReason.WITHDRAWN, b.endReason)
        val c = (engine.play(req(log, CancelMode.REVERSE)) as PlayResult.Queued).handle
        assertEquals(HandleState.CANCELLING, a.state)
        assertTrue(c.isAwaitingHandoff, "新请求仍须等倒放中的前任")
        clock.run(320.0, 1000.0)
        assertEquals(HandleState.ACTIVE, c.state)
    }

    @Test
    fun slotsAreIndependentAndNullSlotNeverPreempts() {
        val engine = VisualEngine(ManualClock())
        val log = Log()
        val a = (engine.play(req(log, slot = "x")) as PlayResult.Started).handle
        val b = (engine.play(req(log, slot = "y")) as PlayResult.Started).handle
        val c = (engine.play(req(log, slot = null)) as PlayResult.Started).handle
        val d = (engine.play(req(log, slot = null)) as PlayResult.Started).handle
        listOf(a, b, c, d).forEach { assertEquals(HandleState.ACTIVE, it.state) }
    }

    @Test
    fun dropOldestWithReverseWaitsForEvictedHandle() {
        val log = Log()
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.DROP_OLDEST, particleBudget = 40))
        val a = (engine.play(req(log, slot = null)) as PlayResult.Started).handle
        clock.run(0.0, 300.0)
        val b = (engine.play(req(log, CancelMode.REVERSE, slot = null)) as PlayResult.Queued).handle
        assertEquals(HandleState.CANCELLING, a.state)
        assertTrue(engine.particleUsage <= 40, "被挤者倒放期间新句柄未启动，不超预算")
        clock.run(320.0, 1000.0)
        assertEquals(EndReason.EVICTED, a.endReason)
        assertEquals(HandleState.ACTIVE, b.state)
    }

    @Test
    fun requestExceedingTotalBudgetIsRejectedWithReason() {
        val engine = VisualEngine(ManualClock(), EngineConfig(overflowStrategy = OverflowStrategy.QUEUE, particleBudget = 20))
        assertEquals(PlayResult.Rejected(RejectReason.OVER_TOTAL_BUDGET), engine.play(req(Log(), slot = null)))
    }

    @Test
    fun rejectionAfterHandoffEndsTheWaitingHandleWithReason() {
        // 小的 A 放得下，大的 B 单个即超总预算：等 A 收回后裁决被拒，B 以 REJECTED 终结
        val log = Log()
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.DROP_NEWEST, particleBudget = 20))
        val a = (engine.play(req(log, w = 8)) as PlayResult.Started).handle
        clock.run(0.0, 300.0)
        val b = (engine.play(req(log, CancelMode.REVERSE, w = 40)) as PlayResult.Queued).handle
        clock.run(320.0, 1000.0)
        assertEquals(HandleState.COMPLETED, a.state)
        assertEquals(HandleState.COMPLETED, b.state)
        assertEquals(EndReason.REJECTED, b.endReason)
        assertEquals(RejectReason.OVER_TOTAL_BUDGET, b.rejectReason)
        assertIs<RenderInstruction.None>(b.currentRender())
    }
}
