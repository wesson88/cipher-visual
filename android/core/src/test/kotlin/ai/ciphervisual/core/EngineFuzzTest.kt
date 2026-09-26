package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 引擎不变式 fuzz：随机 play（含渲染位 / 两种 preemptMode）/ cancel / reportError / 推帧 / release，
 * 覆盖 5 种打满策略，每步断言：
 *
 * 1. 状态回调序列逐步合法（每一跳都能在转移表里找到事件），终态之后无回调；
 * 2. 终态句柄：不在运行列表、不在队列、不在等交接、不持粒子场、渲染指令为 None、渲染面已在终态被通知；
 *    再次 cancel 返回 false（终态吸收）；
 * 3. idle ⇔ 排队中或等交接；运行列表里只有 active / cancelling；
 * 4. 同一渲染位上任意时刻至多一个 active（最新的取消前一次）；
 * 5. 取消交接：同位句柄进入 active 时，同位更早的句柄必须都已终态（前一次回调之后才发起最新的）；
 * 6. 预算：queue / dropNewest / dropOldest 下，任何句柄被放行（→ active）的那一刻、以及每一步之后占用 ≤ 预算
 *    （静态停留释放粒子、reverse 重建过裁决后，不再有绕过裁决的计费路径）；
 * 6b. 停在最终画面（StaticTarget）的句柄不持粒子场；
 * 7. 活性：清空运行句柄后不会有句柄卡在 idle；
 * 8. release() 之后所有句柄终态、帧时钟已退订。
 *
 * 失败信息带 seed，可复现。
 */
class EngineFuzzTest {
    private class Tracked(val handle: VisualHandle, val slot: Any?, val seq: Int) {
        var lastState = HandleState.IDLE
        var notifiedAfterTerminal = false
        val violations = ArrayList<String>()
    }

    private fun block(w: Int) = IntArrayPixels(w, 12, IntArray(w * 12) { 0xFF223344.toInt() })

    @Test
    fun invariantsHoldUnderRandomOperations() {
        for (seed in 0L until 500L) runOnce(seed)
    }

    private fun runOnce(seed: Long) {
        val rng = Mulberry32(seed)
        fun r(n: Int) = (rng.nextDouble() * n).toInt()
        val strategy = OverflowStrategy.entries[r(OverflowStrategy.entries.size)]
        // 单请求最多 30 粒子（40×12 / grid 4）；预算 20..119 → 也会出现「单请求超总预算」被拒的情况
        val budget = 20 + r(100)
        val bounded = strategy == OverflowStrategy.QUEUE || strategy == OverflowStrategy.DROP_NEWEST || strategy == OverflowStrategy.DROP_OLDEST
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = strategy, particleBudget = budget))
        val slots = listOf("slotA", "slotB", "slotC")
        val tracked = ArrayList<Tracked>()
        val byHandle = HashMap<VisualHandle, Tracked>()
        var now = 0.0
        var seq = 0
        val ctx = "seed=$seed strategy=${strategy.wire} budget=$budget"
        // 操作轨迹：失败时附带最近 40 条，便于还原时序
        val trace = ArrayDeque<String>()
        fun log(s: String) {
            trace.addLast(s)
            if (trace.size > 40) trace.removeFirst()
        }
        fun traced() = trace.joinToString(separator = "\n    ", prefix = "\n  轨迹：\n    ")

        val listener = object : VisualListener {
            override fun onStateChanged(handle: VisualHandle, state: HandleState) {
                log("  cb h${handle.id} → ${state.wire} (${handle.endReason ?: ""})")
                val t = byHandle[handle] ?: return // play() 同步期间尚未登记，登记时补齐 lastState
                if (t.lastState.isTerminal) t.violations += "终态 ${t.lastState.wire} 之后又回调 ${state.wire}"
                if (HandleEvent.entries.none { HandleFsm.transition(t.lastState, it) == state }) {
                    t.violations += "非法跳转 ${t.lastState.wire} → ${state.wire}"
                }
                if (state == HandleState.ACTIVE) onActivated(t)
                t.lastState = state
            }

            fun onActivated(t: Tracked) {
                if (bounded && engine.particleUsage > budget) {
                    t.violations += "放行时占用 ${engine.particleUsage} > 预算 $budget"
                }
                if (t.slot != null) {
                    tracked.filter { it.slot == t.slot && it.seq < t.seq && !it.handle.state.isTerminal }.forEach {
                        t.violations += "同位更早的句柄 ${it.handle.id} 尚未终态（${it.handle.state.wire}）就放行了"
                    }
                }
            }
        }

        fun check(step: String) {
            val active = engine.activeHandles
            for (t in tracked) {
                val h = t.handle
                val where = "$ctx step=$step handle=${h.id}"
                if (t.violations.isNotEmpty()) fail("$where ${t.violations}${traced()}")
                assertEquals(h.state, t.lastState, "$where 回调状态与句柄状态不一致")
                if (h.state.isTerminal) {
                    assertFalse(h in active, "$where 终态仍在运行列表")
                    assertFalse(h.isQueued, "$where 终态仍在队列")
                    assertFalse(h.isAwaitingHandoff, "$where 终态仍在等交接")
                    assertFalse(h.holdsParticleData, "$where 终态仍持粒子场")
                    assertEquals(RenderInstruction.None, h.currentRender(), "$where 终态渲染指令非 None")
                    assertTrue(t.notifiedAfterTerminal, "$where 终态未通知渲染面")
                }
                if (h.state == HandleState.ACTIVE && h.currentRender() == RenderInstruction.StaticTarget) {
                    assertFalse(h.holdsParticleData, "$where 停在最终画面仍持粒子场")
                }
                if (h.state == HandleState.IDLE) {
                    assertTrue(h.isQueued || h.isAwaitingHandoff, "$where idle 但既不排队也不等交接（僵尸）")
                    assertFalse(h in active, "$where 未启动句柄出现在运行列表")
                }
            }
            for (h in active) {
                assertTrue(h.state == HandleState.ACTIVE || h.state == HandleState.CANCELLING, "$ctx step=$step 运行列表含 ${h.state}")
            }
            if (bounded) assertTrue(engine.particleUsage <= budget, "$ctx step=$step 占用 ${engine.particleUsage} > 预算 $budget${traced()}")
            for (slot in slots) {
                val n = tracked.count { it.slot == slot && it.handle.state == HandleState.ACTIVE }
                assertTrue(n <= 1, "$ctx step=$step $slot 上同时有 $n 个 active")
            }
        }

        fun play() {
            val slot = if (r(4) == 0) null else slots[r(slots.size)]
            val ir = IrTemplates.dissolve(100.0 + r(900), if (r(3) == 0) HoldMode.JITTER else HoldMode.STATIC)
            val req = PlayRequest(
                ir, block(8 * (1 + r(5))), block(8 * (1 + r(5))), listener,
                reducedMotion = r(8) == 0,
                slot = slot,
                preemptMode = if (r(2) == 0) CancelMode.TEARDOWN else CancelMode.REVERSE,
            )
            log("play slot=$slot mode=${req.preemptMode} reduced=${req.reducedMotion} → next id")
            val h = when (val res = engine.play(req)) {
                is PlayResult.Started -> res.handle
                is PlayResult.Queued -> res.handle
                is PlayResult.Rejected -> return
            }
            log("  = h${h.id} ${h.state.wire} queued=${h.isQueued} awaiting=${h.isAwaitingHandoff}")
            val t = Tracked(h, slot, seq++)
            t.lastState = h.state
            if (h.state == HandleState.ACTIVE) listener.onActivated(t)
            h.frameObserver = { if (h.state.isTerminal) t.notifiedAfterTerminal = true }
            tracked += t
            byHandle[h] = t
        }

        fun pick(): Tracked? = if (tracked.isEmpty()) null else tracked[r(tracked.size)]

        repeat(80) { i ->
            when (r(10)) {
                0, 1, 2, 3 -> play()
                4, 5 -> pick()?.let { t ->
                    val wasTerminal = t.handle.state.isTerminal
                    val mode = if (r(2) == 0) CancelMode.TEARDOWN else CancelMode.REVERSE
                    log("cancel h${t.handle.id} $mode (was ${t.handle.state.wire})")
                    val ok = t.handle.cancel(mode)
                    if (wasTerminal) assertFalse(ok, "$ctx 终态句柄 cancel 应返回 false")
                }
                6 -> pick()?.handle?.let {
                    log("error h${it.id} (was ${it.state.wire})")
                    it.reportError(IllegalStateException("fuzz"))
                }
                else -> {
                    now += r(250)
                    log("frame $now")
                    clock.frame(now)
                }
            }
            check("#$i")
        }

        // 活性：反复清空运行句柄，不应有句柄卡在 idle
        var rounds = 0
        while (tracked.any { it.handle.state == HandleState.IDLE }) {
            assertTrue(++rounds < 100, "$ctx 句柄卡在 idle 无法推进（活性破坏）")
            engine.activeHandles.forEach { it.cancel(CancelMode.TEARDOWN) }
            now += 16
            clock.frame(now)
            check("drain#$rounds")
        }

        // 再造一批（含等交接的）后 release：全部终态、退订帧时钟
        repeat(8) { play() }
        engine.release()
        check("release")
        for (t in tracked) assertTrue(t.handle.state.isTerminal, "$ctx release 后句柄 ${t.handle.id} 非终态：${t.handle.state}")
        assertFalse(clock.subscribed, "$ctx release 后仍订阅帧时钟")
    }
}
