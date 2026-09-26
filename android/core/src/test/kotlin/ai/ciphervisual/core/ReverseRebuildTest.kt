package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 停在最终画面即释放粒子场；reverse 时按需重建并过预算裁决（grid 阶梯），放不下降为 teardown。
 */
class ReverseRebuildTest {
    private fun block(w: Int, color: Int = 0xFF112233.toInt()) = IntArrayPixels(w, 12, IntArray(w * 12) { color })

    private fun req(
        holdMode: HoldMode = HoldMode.STATIC,
        listener: VisualListener? = null,
        w: Int = 40,
    ) = PlayRequest(IrTemplates.dissolve(5000.0, holdMode), block(w), block(w, 0xFF445566.toInt()), listener)

    private class States : VisualListener {
        val events = ArrayList<String>()
        override fun onStateChanged(handle: VisualHandle, state: HandleState) {
            events += state.wire
        }
    }

    @Test
    fun staticHoldReleasesParticlesButKeepsShowingTarget() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val h = (engine.play(req()) as PlayResult.Started).handle
        clock.run(0.0, 400.0)
        assertTrue(h.holdsParticleData)
        clock.run(416.0, 800.0)
        assertEquals(HandleState.ACTIVE, h.state, "画面照常显示，库不自动收回")
        assertEquals(RenderInstruction.StaticTarget, h.currentRender())
        assertFalse(h.holdsParticleData, "停在最终画面后粒子场应已释放")
        assertEquals(0, h.particleCount)
    }

    @Test
    fun jitterHoldKeepsParticles() {
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val h = (engine.play(req(HoldMode.JITTER)) as PlayResult.Started).handle
        clock.run(0.0, 800.0)
        assertTrue(h.holdsParticleData, "jitter 停留粒子一直在动，不释放")
        assertIs<RenderInstruction.Particles>(h.currentRender())
    }

    @Test
    fun reverseFromStaticHoldRebuildsTheIdenticalField() {
        // 种子协议：重建的粒子场与原来逐位一致——倒放到 t 的画面 = 正放到 t 的画面
        val clock = ManualClock()
        val engine = VisualEngine(clock)
        val a = (engine.play(req()) as PlayResult.Started).handle
        val b = (engine.play(req()) as PlayResult.Started).handle
        clock.frame(0.0)
        clock.frame(300.0)
        val forward = (b.currentRender() as RenderInstruction.Particles).frame.let { it.xs.copyOf(it.count) }
        b.cancel(CancelMode.TEARDOWN)
        clock.run(316.0, 900.0)
        assertFalse(a.holdsParticleData)
        assertTrue(a.cancel(CancelMode.REVERSE))
        assertEquals(HandleState.CANCELLING, a.state)
        assertEquals(4, a.gridPx, "预算充足按原网格重建")
        clock.frame(1000.0) // 倒放起点 600
        clock.frame(1300.0) // t = 300
        assertEquals(300.0, a.timeMs)
        val backward = (a.currentRender() as RenderInstruction.Particles).frame.let { it.xs.copyOf(it.count) }
        assertTrue(forward.contentEquals(backward), "重建后倒放画面与原正放一致")
        clock.run(1316.0, 1700.0)
        assertEquals(EndReason.REVERSED, a.endReason)
    }

    @Test
    fun reverseRebuildDegradesGridWhenBudgetIsTight() {
        // 预算 40：B 在变换中占 30；A 静态停留重建 grid 4 需 30 放不下 → grid 8 需 10 放得下
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.QUEUE, particleBudget = 40))
        val a = (engine.play(req()) as PlayResult.Started).handle
        clock.run(0.0, 700.0)
        val b = (engine.play(req()) as PlayResult.Started).handle
        clock.frame(720.0)
        assertEquals(30, engine.particleUsage)
        assertTrue(a.cancel(CancelMode.REVERSE))
        assertEquals(HandleState.CANCELLING, a.state)
        assertEquals(8, a.gridPx)
        assertEquals(40, engine.particleUsage)
        assertEquals(HandleState.ACTIVE, b.state)
    }

    @Test
    fun reverseFallsBackToTeardownWhenEvenCoarsestGridDoesNotFit() {
        // 预算 30：B 占满 30；A 重建最粗一档（grid 16 → 3×1 = 3）也放不下 → 降为 teardown
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.DROP_NEWEST, particleBudget = 30))
        val states = States()
        val a = (engine.play(req(listener = states)) as PlayResult.Started).handle
        clock.run(0.0, 700.0)
        (engine.play(req()) as PlayResult.Started).handle
        clock.frame(720.0)
        assertTrue(a.cancel(CancelMode.REVERSE), "cancel 被接受（只是降级为瞬时收回）")
        assertEquals(HandleState.COMPLETED, a.state)
        assertEquals(EndReason.TEARDOWN, a.endReason, "App 可据此分辨：要求倒放但降为了瞬时收回")
        assertEquals(listOf("active", "completed"), states.events, "不经过 cancelling")
        assertFalse(a.holdsParticleData)
        assertTrue(engine.particleUsage <= 30)
    }

    @Test
    fun preemptWithReverseFromStaticHoldAlsoPassesBudget() {
        // 同位 reverse 交接：前任在静态停留，重建放不下 → 前任直接 teardown（PREEMPTED），新请求随即裁决
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = OverflowStrategy.QUEUE, particleBudget = 30))
        val slot = "v"
        val a = (engine.play(PlayRequest(IrTemplates.dissolve(5000.0), block(40), block(40), slot = slot)) as PlayResult.Started).handle
        clock.run(0.0, 700.0)
        engine.play(PlayRequest(IrTemplates.dissolve(5000.0), block(40), block(40))) // 无渲染位，占满 30
        clock.frame(720.0)
        val c = engine.play(PlayRequest(IrTemplates.dissolve(5000.0), block(8), block(8), slot = slot, preemptMode = CancelMode.REVERSE))
        assertEquals(EndReason.PREEMPTED, a.endReason)
        assertEquals(HandleState.COMPLETED, a.state)
        assertTrue(engine.particleUsage <= 30)
        assertIs<PlayResult.Queued>(c, "预算仍满，新请求按 queue 策略排队")
    }
}
