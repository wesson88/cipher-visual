package ai.ciphervisual.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 引擎不变式 fuzz：随机 play（含渲染位 / 两种 preemptMode / 畸形 IR / 回调内立即取消）/ cancel / reportError /
 * 推帧 / 宿主 View detach（回收内容，之后读像素抛异常，模拟 Android 已回收位图）/ release，覆盖 5 种打满策略。
 * 渲染位由真实的 [RenderSlot] 模拟宿主 View。每步断言：
 *
 * 1. 状态回调序列逐步合法，终态之后无回调；回调里不抛异常逃逸（异常会直接让 fuzz 失败）；
 * 2. 终态句柄：不在运行列表 / 队列 / 等交接、不持粒子场、渲染指令为 None；再次 cancel 返回 false；
 *    渲染面收尾：挂在渲染位上的终态句柄其载荷**恰好释放一次**，未挂渲染位的也收到过终态通知；
 * 3. idle ⇔ 排队中或等交接；运行列表里只有 active / cancelling；
 * 4. 同一渲染位上至多一个 active；同位更早的句柄全部终态后新句柄才放行（取消交接）；
 * 5. 预算：queue / dropNewest / dropOldest 下，每一步之后占用 ≤ 预算；停在最终画面的句柄不持粒子场；
 * 6. QUEUE 严格 FIFO：放行时不存在入队更早（或从未入队却插在排队者前面）的仍在排队的句柄；
 * 7. 畸形 IR 只以 IrInvalidException 被拒；
 * 8. 活性：清空运行句柄后不会有句柄卡在 idle；release() 后全部终态、帧时钟已退订。
 *
 * 失败信息带 seed 与最近 40 步操作轨迹。
 */
class EngineFuzzTest {
    private class Tracked(val handle: VisualHandle, val slot: String?, val seq: Int) {
        var lastState = HandleState.IDLE
        var notifiedAfterTerminal = false
        var releaseCount = 0
        val violations = ArrayList<String>()
    }

    @Test
    fun invariantsHoldUnderRandomOperations() {
        for (seed in 0L until 500L) runOnce(seed)
    }

    private fun runOnce(seed: Long) {
        val rng = Mulberry32(seed)
        fun r(n: Int) = (rng.nextDouble() * n).toInt()
        val strategy = OverflowStrategy.entries[r(OverflowStrategy.entries.size)]
        val budget = 20 + r(100)
        val bounded = strategy == OverflowStrategy.QUEUE || strategy == OverflowStrategy.DROP_NEWEST || strategy == OverflowStrategy.DROP_OLDEST
        val clock = ManualClock()
        val engine = VisualEngine(clock, EngineConfig(overflowStrategy = strategy, particleBudget = budget))
        val slotNames = listOf("slotA", "slotB", "slotC")
        val tracked = ArrayList<Tracked>()
        val byHandle = HashMap<VisualHandle, Tracked>()
        val cancelOnActive = HashSet<VisualHandle>()
        var now = 0.0
        var seq = 0
        val ctx = "seed=$seed strategy=${strategy.wire} budget=$budget"
        val trace = ArrayDeque<String>()
        fun log(s: String) {
            trace.addLast(s)
            if (trace.size > 40) trace.removeFirst()
        }
        fun traced() = trace.joinToString(separator = "\n    ", prefix = "\n  轨迹：\n    ")

        // 每个渲染位一个 RenderSlot，载荷 = 该句柄的内容；释放即回收（之后读像素抛异常）
        class Payload(val handle: VisualHandle, val src: RecyclablePixels, val tgt: RecyclablePixels)
        val renderSlots = slotNames.associateWith {
            RenderSlot<Payload>(
                onRelease = { p ->
                    p.src.recycled = true
                    p.tgt.recycled = true
                    byHandle[p.handle]?.let { t -> t.releaseCount++ }
                },
                onChanged = {},
            )
        }

        val listener = object : VisualListener {
            override fun onStateChanged(handle: VisualHandle, state: HandleState) {
                log("  cb h${handle.id} → ${state.wire} (${handle.endReason ?: ""})")
                val t = byHandle[handle]
                if (t != null) {
                    if (t.lastState.isTerminal) t.violations += "终态 ${t.lastState.wire} 之后又回调 ${state.wire}"
                    if (HandleEvent.entries.none { HandleFsm.transition(t.lastState, it) == state }) {
                        t.violations += "非法跳转 ${t.lastState.wire} → ${state.wire}"
                    }
                    if (state == HandleState.ACTIVE) onActivated(t)
                    t.lastState = state
                }
                if (state == HandleState.ACTIVE && handle in cancelOnActive) {
                    log("  (回调内立即取消 h${handle.id})")
                    handle.cancel(CancelMode.TEARDOWN)
                }
            }

            fun onActivated(t: Tracked) {
                if (bounded && engine.particleUsage > budget) t.violations += "放行时占用 ${engine.particleUsage} > 预算 $budget"
                if (t.slot != null) {
                    tracked.filter { it.slot == t.slot && it.seq < t.seq && !it.handle.state.isTerminal }.forEach {
                        t.violations += "同位更早的句柄 ${it.handle.id} 尚未终态（${it.handle.state.wire}）就放行了"
                    }
                }
                if (strategy == OverflowStrategy.QUEUE) {
                    val mySeq = t.handle.queueSeq
                    tracked.filter { it !== t && it.handle.isQueued && (mySeq < 0 || it.handle.queueSeq < mySeq) }.forEach {
                        t.violations += "插队：h${it.handle.id}（入队 #${it.handle.queueSeq}）仍在排队，h${t.handle.id}（入队 #$mySeq）却先放行"
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
                assertEquals(h.state, t.lastState, "$where 回调状态与句柄状态不一致${traced()}")
                if (h.state.isTerminal) {
                    assertFalse(h in active, "$where 终态仍在运行列表")
                    assertFalse(h.isQueued, "$where 终态仍在队列")
                    assertFalse(h.isAwaitingHandoff, "$where 终态仍在等交接")
                    assertFalse(h.holdsParticleData, "$where 终态仍持粒子场")
                    assertEquals(RenderInstruction.None, h.currentRender(), "$where 终态渲染指令非 None")
                    if (t.slot != null) {
                        assertEquals(1, t.releaseCount, "$where 渲染位载荷释放次数 ${t.releaseCount}（应恰好 1 次）${traced()}")
                    } else {
                        assertTrue(t.notifiedAfterTerminal, "$where 终态未通知渲染面")
                    }
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
            for (slot in slotNames) {
                val n = tracked.count { it.slot == slot && it.handle.state == HandleState.ACTIVE }
                assertTrue(n <= 1, "$ctx step=$step $slot 上同时有 $n 个 active")
            }
        }

        fun play() {
            val slot = if (r(4) == 0) null else slotNames[r(slotNames.size)]
            var ir = IrTemplates.dissolve(100.0 + r(900), if (r(3) == 0) HoldMode.JITTER else HoldMode.STATIC)
            val malformed = r(20) == 0
            if (malformed) ir = ir.copy(phases = ir.phases.mapIndexed { i, p -> if (i == 1) p.copy(range = listOf(150.0)) else p })
            val src = RecyclablePixels(8 * (1 + r(5)), 12)
            val tgt = RecyclablePixels(8 * (1 + r(5)), 12)
            val cancelFast = r(10) == 0
            val req = PlayRequest(
                ir, src, tgt, listener,
                reducedMotion = r(8) == 0,
                slot = slot,
                preemptMode = if (r(2) == 0) CancelMode.TEARDOWN else CancelMode.REVERSE,
            )
            log("play slot=$slot mode=${req.preemptMode} malformed=$malformed cancelOnActive=$cancelFast")
            val res = try {
                engine.play(req)
            } catch (e: IrInvalidException) {
                assertTrue(malformed, "$ctx 合法 IR 被拒：${e.errors}")
                return
            }
            assertFalse(malformed, "$ctx 畸形 IR 未被拒")
            val h = when (res) {
                is PlayResult.Started -> res.handle
                is PlayResult.Queued -> res.handle
                is PlayResult.Rejected -> return
            }
            log("  = h${h.id} ${h.state.wire} queued=${h.isQueued} awaiting=${h.isAwaitingHandoff}")
            val t = Tracked(h, slot, seq++)
            t.lastState = h.state
            tracked += t
            byHandle[h] = t
            if (h.state == HandleState.ACTIVE) listener.onActivated(t)
            if (cancelFast) {
                // 回调里立即取消：已 active 的当场取消（模拟回调时序），未启动的等它 active 时取消
                if (h.state == HandleState.ACTIVE) {
                    log("  (active 回调内取消 h${h.id})")
                    h.cancel(CancelMode.TEARDOWN)
                } else {
                    cancelOnActive += h
                }
            }
            if (slot != null) {
                renderSlots.getValue(slot).attach(h, Payload(h, src, tgt))
            } else {
                h.frameObserver = { if (h.state.isTerminal) t.notifiedAfterTerminal = true }
                if (h.state.isTerminal) t.notifiedAfterTerminal = true
            }
        }

        fun pick(): Tracked? = if (tracked.isEmpty()) null else tracked[r(tracked.size)]

        repeat(80) { i ->
            when (r(12)) {
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
                7 -> {
                    // 宿主 View.detach()：回收该位全部载荷，但不改变句柄状态
                    val slot = slotNames[r(slotNames.size)]
                    log("detach $slot")
                    renderSlots.getValue(slot).detach()
                }
                else -> {
                    now += r(250)
                    log("frame $now")
                    clock.frame(now)
                }
            }
            check("#$i")
        }

        var rounds = 0
        while (tracked.any { it.handle.state == HandleState.IDLE }) {
            assertTrue(++rounds < 100, "$ctx 句柄卡在 idle 无法推进（活性破坏）${traced()}")
            engine.activeHandles.forEach { it.cancel(CancelMode.TEARDOWN) }
            now += 16
            clock.frame(now)
            check("drain#$rounds")
        }

        repeat(8) { play() }
        engine.release()
        check("release")
        for (t in tracked) assertTrue(t.handle.state.isTerminal, "$ctx release 后句柄 ${t.handle.id} 非终态：${t.handle.state}")
        assertFalse(clock.subscribed, "$ctx release 后仍订阅帧时钟")
    }
}
