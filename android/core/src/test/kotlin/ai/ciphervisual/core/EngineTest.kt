package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 手动帧时钟：一次性回调语义同 Choreographer。 */
class ManualClock : FrameClock {
    private val callbacks = ArrayList<FrameCallback>()
    var nowNanos = 0L
    val subscribed get() = callbacks.isNotEmpty()

    override fun postFrameCallback(callback: FrameCallback) {
        callbacks += callback
    }

    override fun removeFrameCallback(callback: FrameCallback) {
        callbacks -= callback
    }

    fun frame(atMs: Double) {
        nowNanos = (atMs * 1_000_000).toLong()
        val cbs = callbacks.toList()
        callbacks.clear()
        cbs.forEach { it.doFrame(nowNanos) }
    }

    /** 以 60Hz 从 fromMs 跑到 toMs */
    fun run(fromMs: Double, toMs: Double) {
        var t = fromMs
        while (t <= toMs) {
            frame(t)
            t += 1000.0 / 60
        }
    }
}

class RecordingListener : VisualListener {
    val events = ArrayList<String>()
    var onAnchorHook: ((VisualHandle, String) -> Unit)? = null
    override fun onAnchor(handle: VisualHandle, anchorId: String) {
        events += "anchor:$anchorId@${handle.timeMs.toInt()}"
        onAnchorHook?.invoke(handle, anchorId)
    }

    override fun onStateChanged(handle: VisualHandle, state: HandleState) {
        events += "state:${state.wire}"
    }
}

class EngineTest {
    // 40×12 的实心块，grid 4 → 10×3 = 30 个样本
    private fun block(w: Int = 40, h: Int = 12, color: Int = 0xFF112233.toInt()) = IntArrayPixels(w, h, IntArray(w * h) { color })

    private fun request(
        holdMs: Double = 1000.0,
        holdMode: HoldMode = HoldMode.STATIC,
        listener: VisualListener? = null,
        reduced: Boolean = false,
        w: Int = 40,
    ) = PlayRequest(IrTemplates.dissolve(holdMs, holdMode), block(w), block(w, color = 0xFF445566.toInt()), listener, reduced)

    @Test
    fun anchorsFireInOrderAndHoldDoesNotAutoRecall() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val l = RecordingListener()
        val h = (engine.play(request(listener = l)) as PlayResult.Started).handle
        assertEquals(HandleState.ACTIVE, h.state)
        clock.run(0.0, 2000.0)
        val anchors = l.events.filter { it.startsWith("anchor") }.map { it.substringBefore('@') }
        assertEquals(listOf("anchor:onBurst", "anchor:onResolve", "anchor:onHoldExpired"), anchors)
        // hold 到期只发锚点，库不自动收回
        assertEquals(HandleState.ACTIVE, h.state)
        assertEquals("hold", phaseAt(IrTemplates.dissolve(1000.0).phases, 1500.0))
        // 锚点全部发完 + static hold → 不再要帧
        assertFalse(clock.subscribed)
        assertEquals(RenderInstruction.StaticTarget, h.currentRender())
    }

    @Test
    fun droppedFramesStillFireEveryCrossedAnchorOnce() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val l = RecordingListener()
        engine.play(request(listener = l))
        clock.frame(0.0)
        clock.frame(5000.0) // 一帧跨过 onResolve 与 onHoldExpired
        assertEquals(listOf("anchor:onBurst@0", "anchor:onResolve@5000", "anchor:onHoldExpired@5000"), l.events.filter { it.startsWith("anchor") })
    }

    @Test
    fun teardownIsImmediateAndReleasesParticles() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val h = (engine.play(request()) as PlayResult.Started).handle
        clock.run(0.0, 200.0)
        assertIs<RenderInstruction.Particles>(h.currentRender())
        assertTrue(h.cancel(CancelMode.TEARDOWN))
        assertEquals(HandleState.COMPLETED, h.state)
        assertEquals(EndReason.TEARDOWN, h.endReason)
        assertEquals(RenderInstruction.None, h.currentRender())
        assertEquals(0, h.particleCount)
        assertEquals(0, engine.particleUsage)
        assertFalse(h.cancel(CancelMode.TEARDOWN), "终态拒绝后续事件")
    }

    @Test
    fun reverseFromHoldPlaysBackToZeroThenCompletes() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val l = RecordingListener()
        val h = (engine.play(request(listener = l)) as PlayResult.Started).handle
        clock.run(0.0, 1200.0)
        assertTrue(h.cancel(CancelMode.REVERSE))
        assertEquals(HandleState.CANCELLING, h.state)
        clock.frame(1300.0)
        assertEquals(600.0, h.timeMs, "从 hold 收回先跳回 morphEnd")
        clock.frame(1600.0)
        assertEquals(300.0, h.timeMs)
        clock.frame(1900.0)
        assertEquals(HandleState.COMPLETED, h.state)
        assertEquals(EndReason.REVERSED, h.endReason)
        assertEquals(listOf("state:active", "state:cancelling", "state:completed"), l.events.filter { it.startsWith("state") })
    }

    @Test
    fun teardownInterruptsReverse() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val h = (engine.play(request()) as PlayResult.Started).handle
        clock.run(0.0, 300.0)
        h.cancel(CancelMode.REVERSE)
        clock.frame(350.0)
        assertTrue(h.cancel(CancelMode.TEARDOWN))
        assertEquals(HandleState.COMPLETED, h.state)
        assertEquals(EndReason.TEARDOWN, h.endReason)
    }

    @Test
    fun appCanRecallInsideHoldExpiredAnchor() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val l = RecordingListener()
        l.onAnchorHook = { h, id -> if (id == Anchors.ON_HOLD_EXPIRED) h.cancel(CancelMode.REVERSE) }
        val h = (engine.play(request(listener = l)) as PlayResult.Started).handle
        clock.run(0.0, 3000.0)
        assertEquals(HandleState.COMPLETED, h.state)
        assertEquals(EndReason.REVERSED, h.endReason)
    }

    @Test
    fun staticHoldCostsNothingJitterHoldCostsParticles() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val s = (engine.play(request(holdMode = HoldMode.STATIC)) as PlayResult.Started).handle
        val j = (engine.play(request(holdMode = HoldMode.JITTER)) as PlayResult.Started).handle
        assertEquals(s.particleCount + j.particleCount, engine.particleUsage)
        clock.run(0.0, 800.0)
        assertEquals(j.particleCount, engine.particleUsage)
        assertIs<RenderInstruction.Particles>(j.currentRender())
    }

    @Test
    fun degradeRaisesGridWhenBudgetIsFull() {
        val clock = ManualClock()
        // 30 粒子 / handle，预算 40：第二个 handle 须降一档（grid 8 → 5×2 = 10 粒子）
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.DEGRADE, particleBudget = 40))
        val a = (engine.play(request()) as PlayResult.Started).handle
        val b = (engine.play(request()) as PlayResult.Started).handle
        assertEquals(4, a.gridPx)
        assertEquals(8, b.gridPx)
        assertEquals(30, a.particleCount)
        assertEquals(10, b.particleCount)
    }

    @Test
    fun queueStartsWhenBudgetFrees() {
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.QUEUE, particleBudget = 40))
        val a = (engine.play(request()) as PlayResult.Started).handle
        val b = (engine.play(request()) as PlayResult.Queued).handle
        assertEquals(HandleState.IDLE, b.state)
        assertTrue(b.isQueued)
        clock.run(0.0, 700.0) // a 进入 static hold，预算释放
        assertEquals(HandleState.ACTIVE, b.state)
        assertEquals(HandleState.ACTIVE, a.state)
    }

    @Test
    fun queuedHandleCanBeWithdrawn() {
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.QUEUE, particleBudget = 40))
        engine.play(request())
        val l = RecordingListener()
        val b = (engine.play(request(listener = l)) as PlayResult.Queued).handle
        b.cancel(CancelMode.TEARDOWN).also { assertTrue(it) }
        assertFalse(b.isQueued)
        assertEquals(HandleState.COMPLETED, b.state)
        assertEquals(EndReason.WITHDRAWN, b.endReason)
        clock.run(0.0, 700.0)
        assertEquals(HandleState.COMPLETED, b.state, "撤回后不会被队列再启动")
        assertFalse(b.cancel(CancelMode.TEARDOWN), "终态拒绝后续事件")
        assertEquals(listOf("state:completed"), l.events, "撤回照常回调终态")
    }

    @Test
    fun dropNewestRejects() {
        val engine = VisualEngine(ManualClock(), EngineConfig(overflowStrategy = OverflowStrategy.DROP_NEWEST, particleBudget = 40))
        assertIs<PlayResult.Started>(engine.play(request()))
        assertEquals(PlayResult.Rejected, engine.play(request()))
    }

    @Test
    fun dropOldestEvictsEarliestRunning() {
        val engine = VisualEngine(ManualClock(), EngineConfig(overflowStrategy = OverflowStrategy.DROP_OLDEST, particleBudget = 40))
        val a = (engine.play(request()) as PlayResult.Started).handle
        val b = (engine.play(request()) as PlayResult.Started).handle
        assertEquals(HandleState.COMPLETED, a.state)
        assertEquals(EndReason.EVICTED, a.endReason)
        assertEquals(HandleState.ACTIVE, b.state)
    }

    @Test
    fun reducedMotionCrossfadesWithSameAnchorsAndZeroCost() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val l = RecordingListener()
        val h = (engine.play(request(listener = l, reduced = true)) as PlayResult.Started).handle
        assertEquals(0, engine.particleUsage)
        clock.frame(0.0)
        assertEquals(RenderInstruction.Crossfade(0f), h.currentRender())
        clock.run(16.0, 2000.0)
        assertEquals(RenderInstruction.Crossfade(1f), h.currentRender())
        assertEquals(3, l.events.count { it.startsWith("anchor") })
    }

    @Test
    fun invalidIrIsRejectedBeforePlay() {
        val bad = IrTemplates.dissolve(1000.0).copy(effect = "fade")
        val e = runCatching { VisualEngine(ManualClock()).play(PlayRequest(bad, block(), block())) }.exceptionOrNull()
        assertIs<IrInvalidException>(e)
        assertTrue(IrError.EFFECT_UNSUPPORTED in e.errors)
    }

    @Test
    fun renderErrorFailsHandleVisibly() {
        val engine = VisualEngine(ManualClock())
        val l = RecordingListener()
        val h = (engine.play(request(listener = l)) as PlayResult.Started).handle
        h.reportError(IllegalStateException("canvas"))
        assertEquals(HandleState.FAILED, h.state)
        assertEquals(EndReason.ERROR, h.endReason)
        assertEquals("state:failed", l.events.last())
    }

    @Test
    fun preparedSourceIsUsed() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val src = block()
        val prepared = engine.prepare(src, IrTemplates.DEFAULT_SEED, IrTemplates.DEFAULT_GRID_PX, IrTemplates.DEFAULT_JITTER_PX)
        val a = (engine.play(PlayRequest(IrTemplates.dissolve(1000.0), src, block(), prepared = prepared)) as PlayResult.Started).handle
        val b = (engine.play(PlayRequest(IrTemplates.dissolve(1000.0), src, block())) as PlayResult.Started).handle
        clock.frame(0.0); clock.frame(200.0)
        val fa = (a.currentRender() as RenderInstruction.Particles).frame
        val fb = (b.currentRender() as RenderInstruction.Particles).frame
        assertTrue(fa.xs.copyOf(fa.count).contentEquals(fb.xs.copyOf(fb.count)), "预采样与现采样结果一致（确定性）")
    }

    @Test
    fun releaseTearsDownEverything() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val h = (engine.play(request()) as PlayResult.Started).handle
        engine.release()
        assertEquals(HandleState.COMPLETED, h.state)
        assertFalse(clock.subscribed)
        assertNull(engine.activeHandles.firstOrNull())
    }
}
