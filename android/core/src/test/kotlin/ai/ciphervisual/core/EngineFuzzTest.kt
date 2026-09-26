package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 引擎不变式 fuzz：随机 play / cancel / reportError / 推帧 / release，覆盖 5 种打满策略，每步断言：
 *
 * 1. 状态回调序列逐步合法（每一跳都能在转移表里找到事件），终态之后无回调；
 * 2. 终态句柄：不在运行列表、不在队列、不持粒子场、渲染指令为 None、渲染面已在终态被通知过；
 *    再次 cancel 返回 false（终态吸收）；
 * 3. idle ⇔ 排队中；运行列表里只有 active / cancelling；
 * 4. 活性：清空运行句柄后队列必能排空（单请求不超总预算的前提下）；
 * 5. release() 之后所有句柄终态、帧时钟已退订。
 *
 * 用手写用例抓不到的跨步骤 bug（如撤回路径漏收尾、release 留僵尸）靠这组断言兜住。失败信息带 seed，可复现。
 */
class EngineFuzzTest {
    private class Tracked(val handle: VisualHandle) : VisualListener {
        var lastState = HandleState.IDLE
        var notifiedAfterTerminal = false
        val violations = ArrayList<String>()

        override fun onStateChanged(handle: VisualHandle, state: HandleState) {
            if (lastState.isTerminal) violations += "终态 ${lastState.wire} 之后又回调 ${state.wire}"
            if (HandleEvent.entries.none { HandleFsm.transition(lastState, it) == state }) {
                violations += "非法跳转 ${lastState.wire} → ${state.wire}"
            }
            lastState = state
        }
    }

    private fun block(w: Int) = IntArrayPixels(w, 12, IntArray(w * 12) { 0xFF223344.toInt() })

    @Test
    fun invariantsHoldUnderRandomOperations() {
        for (seed in 0L until 400L) runOnce(seed)
    }

    private fun runOnce(seed: Long) {
        val rng = Mulberry32(seed)
        fun r(n: Int) = (rng.nextDouble() * n).toInt()
        val strategy = OverflowStrategy.entries[r(OverflowStrategy.entries.size)]
        // 单请求最多 30 粒子（40×12 / grid 4），预算 ≥ 40：保证单请求不超总预算，活性断言才成立
        val budget = 40 + r(80)
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = strategy, particleBudget = budget))
        val tracked = ArrayList<Tracked>()
        var now = 0.0
        val ctx = "seed=$seed strategy=${strategy.wire} budget=$budget"

        fun check(step: String) {
            val active = engine.activeHandles
            for (t in tracked) {
                val h = t.handle
                val where = "$ctx step=$step handle=${h.id}"
                if (t.violations.isNotEmpty()) fail("$where ${t.violations}")
                assertEquals(h.state, t.lastState, "$where 回调状态与句柄状态不一致")
                if (h.state.isTerminal) {
                    assertFalse(h in active, "$where 终态仍在运行列表")
                    assertFalse(h.isQueued, "$where 终态仍在队列")
                    assertFalse(h.holdsParticleData, "$where 终态仍持粒子场")
                    assertEquals(RenderInstruction.None, h.currentRender(), "$where 终态渲染指令非 None")
                    assertTrue(t.notifiedAfterTerminal, "$where 终态未通知渲染面")
                }
                if (h.state == HandleState.IDLE) assertTrue(h.isQueued, "$where idle 但不在队列（僵尸）")
                if (h.isQueued) assertFalse(h in active, "$where 排队句柄出现在运行列表")
            }
            for (h in active) {
                assertTrue(h.state == HandleState.ACTIVE || h.state == HandleState.CANCELLING, "$ctx step=$step 运行列表含 ${h.state}")
            }
        }

        fun play() {
            val holder = arrayOfNulls<Tracked>(1)
            val proxy = object : VisualListener {
                override fun onStateChanged(handle: VisualHandle, state: HandleState) {
                    holder[0]?.onStateChanged(handle, state)
                }
            }
            val ir = IrTemplates.dissolve(100.0 + r(900), if (r(3) == 0) HoldMode.JITTER else HoldMode.STATIC)
            val req = PlayRequest(ir, block(8 * (1 + r(5))), block(8 * (1 + r(5))), proxy, reducedMotion = r(8) == 0)
            val res = engine.play(req)
            val h = when (res) {
                is PlayResult.Started -> res.handle
                is PlayResult.Queued -> res.handle
                PlayResult.Rejected -> return
            }
            val t = Tracked(h)
            // 同步 play 期间已发生的回调（Started 时已到 active）
            t.lastState = h.state
            holder[0] = t
            h.frameObserver = { if (h.state.isTerminal) t.notifiedAfterTerminal = true }
            tracked += t
        }

        fun pick(): Tracked? = if (tracked.isEmpty()) null else tracked[r(tracked.size)]

        repeat(80) { i ->
            when (r(10)) {
                0, 1, 2 -> play()
                3, 4 -> pick()?.let { t ->
                    val wasTerminal = t.handle.state.isTerminal
                    val ok = t.handle.cancel(if (r(2) == 0) CancelMode.TEARDOWN else CancelMode.REVERSE)
                    if (wasTerminal) assertFalse(ok, "$ctx 终态句柄 cancel 应返回 false")
                }
                5 -> pick()?.handle?.reportError(IllegalStateException("fuzz"))
                else -> {
                    now += r(250)
                    clock.frame(now)
                }
            }
            check("#$i")
        }

        // 活性：反复清空运行句柄，队列必能排空
        var rounds = 0
        while (tracked.any { it.handle.isQueued }) {
            assertTrue(++rounds < 100, "$ctx 队列无法排空（活性破坏）")
            engine.activeHandles.forEach { it.cancel(CancelMode.TEARDOWN) }
            now += 16
            clock.frame(now)
            check("drain#$rounds")
        }

        // 再造一批排队句柄后 release：全部终态、退订帧时钟
        repeat(6) { play() }
        engine.release()
        check("release")
        for (t in tracked) assertTrue(t.handle.state.isTerminal, "$ctx release 后句柄 ${t.handle.id} 非终态：${t.handle.state}")
        assertFalse(clock.subscribed, "$ctx release 后仍订阅帧时钟")
    }
}
