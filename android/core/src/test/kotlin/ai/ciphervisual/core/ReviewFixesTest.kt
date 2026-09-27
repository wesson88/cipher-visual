package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 可回收像素源：回收后读取抛异常，模拟 Android 已 recycle 的 Bitmap。 */
class RecyclablePixels(override val width: Int, override val height: Int, private val color: Int = 0xFF112233.toInt()) : PixelSource {
    var recycled = false
    override val isAvailable: Boolean get() = !recycled
    override fun argb(x: Int, y: Int): Int {
        check(!recycled) { "Can't call getPixel() on a recycled bitmap" }
        return color
    }
}

/**
 * 全仓 code-review（HEAD 3b999a3）5 项发现的回归用例。
 */
class ReviewFixesTest {
    private class Log : VisualListener {
        val events = ArrayList<String>()
        override fun onStateChanged(handle: VisualHandle, state: HandleState) {
            events += "h${handle.id}:${state.wire}" + (handle.endReason?.let { "/$it" } ?: "")
        }
    }

    private fun req(
        src: PixelSource = RecyclablePixels(40, 12),
        tgt: PixelSource = RecyclablePixels(40, 12),
        listener: VisualListener? = null,
        slot: Any? = null,
        mode: CancelMode = CancelMode.TEARDOWN,
    ) = PlayRequest(IrTemplates.dissolve(1000.0), src, tgt, listener, slot = slot, preemptMode = mode)

    // ---------------------------------------------------------------- #1

    @Test
    fun waitingHandleWhoseContentWasRecycledIsRejectedInsteadOfCrashing() {
        val log = Log()
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val a = (engine.play(req(listener = log, slot = "v")) as PlayResult.Started).handle
        clock.run(0.0, 300.0)
        val src = RecyclablePixels(40, 12)
        val tgt = RecyclablePixels(40, 12)
        val b = (engine.play(req(src, tgt, log, "v", CancelMode.REVERSE)) as PlayResult.Queued).handle
        src.recycled = true // 宿主 View.detach() 回收了等交接句柄的位图
        tgt.recycled = true
        clock.run(320.0, 1000.0) // A 倒放完 → B 首次建粒子场读像素失败
        assertEquals(HandleState.COMPLETED, a.state)
        assertEquals(EndReason.REJECTED, b.endReason)
        assertEquals(RejectReason.CONTENT_UNAVAILABLE, b.rejectReason)
        // 引擎状态完整：后续请求照常工作
        assertIs<PlayResult.Started>(engine.play(req(slot = "v")))
    }

    // ---------------------------------------------------------------- #2

    @Test
    fun queueIsStrictFifoSmallRequestsDoNotJumpAhead() {
        val log = Log()
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.QUEUE, particleBudget = 40))
        engine.play(req(listener = log)) // h1：30
        val big = (engine.play(req(listener = log)) as PlayResult.Queued).handle // h2：30 放不下 → 排队
        val small = engine.play(req(RecyclablePixels(8, 12), RecyclablePixels(8, 12), log)) // h3：6，放得下但前面有人排队
        assertIs<PlayResult.Queued>(small, "严格 FIFO：前面有人排队，放得下也不插队")
        clock.run(0.0, 800.0)
        assertEquals(HandleState.ACTIVE, big.state)
        assertEquals(HandleState.ACTIVE, (small as PlayResult.Queued).handle.state)
        val order = log.events.filter { it.endsWith(":active") }
        assertEquals(listOf("h1:active", "h2:active", "h3:active"), order, "按入队顺序放行")
    }

    @Test
    fun handoffWaiterUnderQueueGoesToTheBackWhenOthersAreQueued() {
        val log = Log()
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.QUEUE, particleBudget = 40))
        val a = (engine.play(req(listener = log, slot = "v")) as PlayResult.Started).handle // h1：30，渲染位 v
        clock.run(0.0, 300.0)
        val q = (engine.play(req(listener = log)) as PlayResult.Queued).handle // h2：排队
        val w = (engine.play(req(listener = log, slot = "v", mode = CancelMode.REVERSE)) as PlayResult.Queued).handle // h3：等 h1 倒放
        clock.run(320.0, 1000.0)
        assertEquals(HandleState.COMPLETED, a.state)
        assertTrue(q.state == HandleState.ACTIVE, "先入队的 h2 先放行")
        assertTrue(w.isQueued, "交接完成后前面有人排队 → 排到队尾，而不是插队")
        clock.run(1016.0, 2200.0) // h2 进入静态停留释放预算后 h3 才放行
        assertEquals(listOf("h1:active", "h2:active", "h3:active"), log.events.filter { it.endsWith(":active") })
        assertEquals(HandleState.ACTIVE, w.state)
    }

    // ---------------------------------------------------------------- #3

    @Test
    fun malformedPhaseRangeIsReportedAsValidationError() {
        val base = IrTemplates.dissolve(1000.0)
        for (bad in listOf(listOf(150.0), emptyList(), listOf(150.0, 350.0, 400.0))) {
            val ir = base.copy(phases = base.phases.mapIndexed { i, p -> if (i == 1) p.copy(range = bad) else p })
            assertEquals(setOf(IrError.PHASE_RANGE), IrValidator.validate(ir), "range=$bad")
            assertFailsWith<IrInvalidException> { VisualEngine(ManualClock()).play(req().let { PlayRequest(ir, it.source, it.target) }) }
        }
    }

    // ---------------------------------------------------------------- #5

    private class Boom : Error("boom")

    @Test
    fun playThatThrowsLeavesNoOrphanInTheSlot() {
        val log = Log()
        val engine = VisualEngine(ManualClock())
        engine.play(req(listener = log, slot = "v")) // h1
        val exploding = object : PixelSource {
            override val width = 40
            override val height = 12
            override fun argb(x: Int, y: Int): Int = throw Boom()
        }
        assertFailsWith<Boom> { engine.play(req(src = exploding, listener = log, slot = "v")) } // h2 抛出
        log.events.clear()
        val c = (engine.play(req(listener = log, slot = "v")) as PlayResult.Started).handle
        assertEquals(listOf("h${c.id}:active"), log.events, "抛出的请求不应残留在渲染位里再收到终态回调")
    }

    @Test
    fun cancelInsideActiveCallbackReleasesRenderSlotPayloadImmediately() {
        val engine = VisualEngine(ManualClock())
        val canceller = object : VisualListener {
            override fun onStateChanged(handle: VisualHandle, state: HandleState) {
                if (state == HandleState.ACTIVE) handle.cancel(CancelMode.TEARDOWN)
            }
        }
        val released = ArrayList<String>()
        val slot = RenderSlot<String>(onRelease = { released += it }, onLayoutChanged = {})
        val h = (engine.play(req(listener = canceller, slot = slot)) as PlayResult.Started).handle
        assertTrue(h.state.isTerminal, "play 返回 Started，但句柄已在回调里终态")
        slot.attach(h, "payload")
        assertEquals(listOf("payload"), released, "错过了终态通知也要在挂载时立即释放")
        assertEquals(null, slot.currentHandle)
    }
}
