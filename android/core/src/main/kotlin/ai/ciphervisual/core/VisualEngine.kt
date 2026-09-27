package ai.ciphervisual.core

/**
 * 引擎配置。`particleBudget` 默认取硬件档的默认预算（待真机标定）。
 */
public data class EngineConfig(
    val hardwareTier: HardwareTier = HardwareTier.LINEAR_X_FULL,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.DEGRADE,
    val particleBudget: Int = hardwareTier.defaultParticleBudget,
)

/** 播放请求。`prepared` 可选：`prepare(source)` 预采样的结果，命中时 play 零采样延迟。 */
public class PlayRequest(
    public val ir: TimelineIr,
    public val source: PixelSource,
    public val target: PixelSource,
    public val listener: VisualListener? = null,
    /** 系统「减少动效」开启时由平台层置 true：降级为淡入淡出，锚点时序不变 */
    public val reducedMotion: Boolean = false,
    public val prepared: PreparedSource? = null,
    /**
     * 渲染位（平台层传入 View）。同一渲染位上「最新的取消前一次」：新请求先取消前一次，
     * 前一次终态回调发出后新请求才启动（取消交接）。null = 不参与渲染位互斥。
     */
    public val slot: Any? = null,
    /** 取消前一次（同渲染位前任 / dropOldest 挤掉的）用哪种收回：默认 TEARDOWN 瞬时，可选 REVERSE（等倒放完再交接） */
    public val preemptMode: CancelMode = CancelMode.TEARDOWN,
)

/** 回调在帧时钟线程（Android 主线程）上发出；回调里可直接调 `handle.cancel(...)`。 */
public interface VisualListener {
    /** 到达 IR 锚点。App 在这里编排触觉/音效/埋点——库不订阅任何其他库。 */
    public fun onAnchor(handle: VisualHandle, anchorId: String) {}

    public fun onStateChanged(handle: VisualHandle, state: HandleState) {}
}

public sealed interface PlayResult {
    public data class Started(val handle: VisualHandle) : PlayResult

    /**
     * 未立即启动，handle 停在 idle：`QUEUE` 策略排队中，或在等前一次取消完成（取消交接）。
     * 之后自动启动；也可能以 WITHDRAWN（App cancel）/ PREEMPTED（被更新的请求取代）/ REJECTED（交接后裁决拒绝）进入终态。
     */
    public data class Queued(val handle: VisualHandle) : PlayResult

    /** 裁决拒绝，未产生句柄。 */
    public data class Rejected(val reason: RejectReason) : PlayResult
}

/** 终态原因。 */
public enum class EndReason {
    TEARDOWN,
    REVERSED,

    /** 被 `DROP_OLDEST` 挤掉 */
    EVICTED,
    ERROR,

    /** 未启动即被 App 撤回 */
    WITHDRAWN,

    /** 同渲染位上被更新的请求取代 */
    PREEMPTED,

    /** 等待交接后重新裁决被拒（原因见 `VisualHandle.rejectReason`） */
    REJECTED,
}

/** 某个 source 在 grid 阶梯各档上的预采样。 */
public class PreparedSource internal constructor(
    internal val source: PixelSource,
    internal val ladder: List<SampledSource>,
)

/** 渲染指令：平台渲染器每次绘制时从句柄读取。 */
public sealed interface RenderInstruction {
    /** 什么都不画（idle / 终态） */
    public data object None : RenderInstruction

    /** 画粒子 */
    public class Particles(public val frame: ParticleFrame) : RenderInstruction

    /** static hold：直接画 target 内容（一次绘制后可缓存，≈0 开销） */
    public data object StaticTarget : RenderInstruction

    /** 减少动效：source 以 (1-targetAlpha)、target 以 targetAlpha 叠画 */
    public data class Crossfade(val targetAlpha: Float) : RenderInstruction
}

/**
 * 动效引擎：时间轴推进 + 锚点分发 + 粒子预算。单线程约束——所有调用与回调都在帧时钟所在线程。
 *
 * 库只**发**锚点与帧，不监听切后台/锁屏/滚动，不自动收回（hold 到期只发 `onHoldExpired`）。
 */
public class VisualEngine(
    private val clock: FrameClock,
    public val config: EngineConfig = EngineConfig(),
) {
    private val running = ArrayList<VisualHandle>()
    private val queue = ArrayDeque<VisualHandle>()
    /** 渲染位 → 该位上所有未终态句柄（按发起先后）。不能只记「最新一个」：最新的被撤回时，更早的可能仍在倒放。 */
    private val slotMembers = HashMap<Any, MutableList<VisualHandle>>()
    private var releasing = false
    private var nextQueueSeq = 0L
    private var nextId = 1L

    /** 帧订阅：NONE / IMMEDIATE（下一帧）/ DELAYED（画面静止，只等下一个锚点） */
    private enum class Sub { NONE, IMMEDIATE, DELAYED }
    private var sub = Sub.NONE
    private var settlingFrame = false
    private val frameCallback = FrameCallback { onFrame(it) }

    /** 当前正在耗预算的粒子数（每帧口径）。 */
    public val particleUsage: Int get() = running.sumOf { it.cost() }

    public val activeHandles: List<VisualHandle> get() = running.toList()

    /** 预采样 source（grid 阶梯三档）。何时预热归 App。 */
    public fun prepare(source: PixelSource, seed: Long, gridPx: Int, jitterPx: Int): PreparedSource =
        PreparedSource(source, gridLadder(gridPx).map { ParticleField.sampleSource(seed, it, jitterPx, source) })

    public fun play(request: PlayRequest): PlayResult {
        IrValidator.requireValid(request.ir)
        val handle = VisualHandle(nextId++, this, request)
        request.slot?.let { slot ->
            // 同位所有未终态的前任（在跑的 / 在倒放的 / 还没启动的）都由新请求取消，未即时终态的等它们交接。
            // 先撤回还没启动的：否则取消在跑的会级联放行它们，出现「刚 active 就被取消」的闪现
            val prevs = slotMembers[slot].orEmpty().sortedBy { it.state != HandleState.IDLE }
            for (prev in prevs) {
                preempt(prev, request.preemptMode, EndReason.PREEMPTED)
                if (!prev.state.isTerminal) handle.awaitHandoff(prev)
            }
            // 前任终态时会把空列表整个移出 map，这里必须重新取
            slotMembers.getOrPut(slot) { ArrayList() } += handle
        }
        val result = try {
            if (handle.awaiting.isNotEmpty()) {
                PlayResult.Queued(handle)
            } else {
                drainQueue()
                admitOrWait(handle, announced = false)
            }
        } catch (e: Throwable) {
            // 抛出去的请求（含监听器在 active 回调里抛异常）调用方从未拿到：从队列 / 运行列表 / 渲染位里全部撤掉，
            // 静默终态（监听器正是抛异常的一方，不再回调它），否则会留下继续发锚点、永不终态的幽灵句柄
            queue.remove(handle)
            handle.queued = false
            handle.clearAwaiting()
            removeFromSlot(handle)
            running.remove(handle)
            handle.abandon()
            updateSubscription()
            throw e
        }
        updateSubscription()
        return result
    }

    /** 销毁：撤回全部未启动句柄、teardown 全部运行句柄、退订帧时钟。生命周期清理，不是收回策略。 */
    public fun release() {
        releasing = true
        try {
            for (h in (queue.toList() + running.flatMap { it.waiters }).distinct()) withdraw(h, EndReason.WITHDRAWN)
            for (h in running.toList()) teardown(h, EndReason.TEARDOWN)
        } finally {
            // 监听器在终态回调里抛异常也不能让引擎卡在 releasing（否则之后的交接放行全部失效）
            slotMembers.clear()
            releasing = false
            updateSubscription()
        }
    }

    // ---------------------------------------------------------------- 句柄控制（VisualHandle 转调）

    internal fun cancel(handle: VisualHandle, mode: CancelMode): Boolean {
        if (handle.state == HandleState.IDLE) {
            // 未启动（排队 / 等交接）：mode 无意义，直接撤回
            withdraw(handle, EndReason.WITHDRAWN)
            drainQueue()
            updateSubscription()
            return true
        }
        return when (mode) {
            CancelMode.TEARDOWN -> {
                if (HandleFsm.transition(handle.state, HandleEvent.CANCEL_TEARDOWN) == null) return false
                teardown(handle, EndReason.TEARDOWN)
                drainQueue()
                updateSubscription()
                true
            }
            CancelMode.REVERSE -> {
                if (HandleFsm.transition(handle.state, HandleEvent.CANCEL_REVERSE) == null) return false
                // 预算放不下重建时降为 teardown：腾出的预算可能放行排队者
                if (!beginReverse(handle, EndReason.REVERSED)) drainQueue()
                updateSubscription()
                true
            }
        }
    }

    internal fun reportError(handle: VisualHandle) {
        if (HandleFsm.transition(handle.state, HandleEvent.ERROR) == null) return
        handle.endReason = EndReason.ERROR
        finish(handle, HandleState.FAILED)
        drainQueue()
        updateSubscription()
    }

    // ---------------------------------------------------------------- 内部

    private fun gridLadder(gridPx: Int): List<Int> = List(Admission.LADDER_STEPS) { gridPx shl it }

    /**
     * @param ahead 排在它前面的排队数（新请求 / 交接后放行 = 当前队长；队头出队 = 0）
     */
    private fun decide(handle: VisualHandle, ahead: Int): AdmissionDecision {
        // 等交接 / 排队期间宿主可能已回收内容（View.detach）：不可读就拒绝，不去读像素。
        // 只查可用性、不吞异常——粒子场构建里的其它异常是库自身 bug，必须暴露
        if (!handle.request.source.isAvailable || !handle.request.target.isAvailable) {
            return AdmissionDecision.Reject(RejectReason.CONTENT_UNAVAILABLE)
        }
        val running = running.map { RunningCost(it.id, it.cost()) }
        if (handle.request.reducedMotion) {
            return Admission.decide(config.overflowStrategy, config.particleBudget, running, listOf(0), ahead)
        }
        val usage = running.sumOf { it.cost }
        val c0 = handle.fieldAt(0).size
        val ladder = if (config.overflowStrategy == OverflowStrategy.DEGRADE && usage + c0 > config.particleBudget) {
            List(Admission.LADDER_STEPS) { handle.fieldAt(it).size }
        } else {
            listOf(c0)
        }
        return Admission.decide(config.overflowStrategy, config.particleBudget, running, ladder, ahead)
    }

    /**
     * 裁决并启动；需要挤占且被挤者走 reverse 时进入等待（交接完成后由 [finish] 重新裁决）。
     * @param announced 句柄是否已作为 Queued 交给调用方（决定拒绝时走终态还是直接返回 Rejected）
     */
    private fun admitOrWait(handle: VisualHandle, announced: Boolean): PlayResult {
        return when (val d = decide(handle, queue.size)) {
            is AdmissionDecision.Admit -> {
                for (id in d.evict) {
                    val victim = running.firstOrNull { it.id == id } ?: continue
                    preempt(victim, handle.request.preemptMode, EndReason.EVICTED)
                    if (!victim.state.isTerminal) handle.awaitHandoff(victim)
                }
                when {
                    handle.awaiting.isNotEmpty() -> {
                        handle.dropFieldCache() // 等交接期间不持粒子场，放行时重建
                        PlayResult.Queued(handle)
                    }
                    // 挤占会触发被挤者的 finish → 级联放行等它交接的句柄，可能已吃掉腾出的预算：重新裁决而不是按旧计划启动
                    d.evict.isNotEmpty() -> admitOrWait(handle, announced)
                    else -> {
                        start(handle, d.gridLevel)
                        PlayResult.Started(handle)
                    }
                }
            }
            AdmissionDecision.Queue -> {
                handle.queued = true
                handle.queueSeq = nextQueueSeq++
                queue.addLast(handle)
                handle.dropFieldCache() // 排队期间不持粒子场（只为判超总预算才建的），出队时重建
                PlayResult.Queued(handle)
            }
            is AdmissionDecision.Reject -> {
                handle.rejectReason = d.reason
                if (announced) {
                    withdraw(handle, EndReason.REJECTED)
                } else {
                    // 从未交给调用方：只清理渲染位登记与预建粒子场
                    removeFromSlot(handle)
                    handle.releaseResources()
                }
                PlayResult.Rejected(d.reason)
            }
        }
    }

    private fun start(handle: VisualHandle, gridLevel: Int) {
        handle.queued = false
        if (!handle.request.reducedMotion) handle.adopt(gridLevel)
        running += handle
        handle.setState(HandleFsm.transition(handle.state, HandleEvent.PLAY)!!)
        updateSubscription()
    }

    /** 取消前一次：未启动的直接撤回；TEARDOWN 瞬时终态；REVERSE 进入倒放（已在倒放的保持）。 */
    private fun preempt(h: VisualHandle, mode: CancelMode, reason: EndReason) {
        when {
            h.state == HandleState.IDLE -> withdraw(h, reason)
            mode == CancelMode.TEARDOWN -> teardown(h, reason)
            h.state == HandleState.ACTIVE -> beginReverse(h, reason)
            else -> Unit
        }
    }

    /**
     * 发起 reverse。静态停留时粒子场已释放，须重建——重建和新请求一样占预算，所以要过裁决：
     * 按 grid 阶梯（grid, 2·grid, 4·grid）取第一档放得下的（收回动画画质不重要，与打满策略无关）；
     * 最粗一档仍放不下就降为 teardown 瞬时收回（主动收回的 endReason 记 TEARDOWN 而非 REVERSED，App 可分辨）。
     * 种子协议保证重建出的粒子场与原来逐位一致，倒放画面不变。
     *
     * @return true = 进入倒放；false = 已降为 teardown（句柄已终态）
     */
    private fun beginReverse(h: VisualHandle, reason: EndReason): Boolean {
        if (!h.request.reducedMotion && !h.hasParticles) {
            val level = reverseLevel(h)
            if (level == null) {
                teardown(h, if (reason == EndReason.REVERSED) EndReason.TEARDOWN else reason)
                return false
            }
            h.adopt(level)
        }
        startReverse(h, reason)
        return true
    }

    private fun reverseLevel(h: VisualHandle): Int? {
        // 内容已被宿主提前回收：不能重建倒放，按放不下处理（降为 teardown）
        if (!h.request.source.isAvailable || !h.request.target.isAvailable) return null
        val usage = particleUsage
        for (level in 0 until Admission.LADDER_STEPS) {
            if (usage + h.fieldAt(level).size <= config.particleBudget) return level
        }
        return null
    }

    private fun startReverse(h: VisualHandle, reason: EndReason) {
        // 从 hold 收回时先跳回 target 队形（morphEnd），再沿时间轴倒放到 0
        h.reverseOriginMs = minOf(h.timeMs, h.knobs.morphEnd)
        h.reverseStartNanos = null
        h.reverseEndReason = reason
        h.setState(HandleFsm.transition(h.state, HandleEvent.CANCEL_REVERSE)!!)
    }

    /** 未启动即退出：idle --withdraw--> completed。走统一收尾——释放预建的粒子场并通知渲染面。 */
    private fun withdraw(handle: VisualHandle, reason: EndReason) {
        queue.remove(handle)
        handle.queued = false
        handle.clearAwaiting()
        handle.endReason = reason
        finish(handle, HandleFsm.transition(handle.state, HandleEvent.WITHDRAW)!!)
    }

    private fun teardown(handle: VisualHandle, reason: EndReason) {
        handle.endReason = reason
        finish(handle, HandleState.COMPLETED)
    }

    /** 统一收尾。终态回调发出**之后**才放行等它交接的句柄——「前一次拿到回调后再发起最新的」。 */
    private fun finish(handle: VisualHandle, terminal: HandleState) {
        running.remove(handle)
        handle.releaseResources()
        handle.setState(terminal)
        handle.notifyFrame()
        removeFromSlot(handle)
        val waiters = handle.waiters.toList()
        handle.waiters.clear()
        for (w in waiters) {
            w.awaiting.remove(handle)
            if (w.awaiting.isEmpty() && w.state == HandleState.IDLE && !releasing) admitOrWait(w, announced = true)
        }
    }

    private fun removeFromSlot(handle: VisualHandle) {
        val slot = handle.request.slot ?: return
        val members = slotMembers[slot] ?: return
        members.remove(handle)
        if (members.isEmpty()) slotMembers.remove(slot)
    }

    private fun drainQueue() {
        while (queue.isNotEmpty()) {
            val head = queue.first()
            when (val d = decide(head, 0)) {
                is AdmissionDecision.Admit -> {
                    queue.removeFirst()
                    start(head, d.gridLevel)
                }
                is AdmissionDecision.Reject -> {
                    head.rejectReason = d.reason
                    withdraw(head, EndReason.REJECTED)
                }
                AdmissionDecision.Queue -> {
                    head.dropFieldCache()
                    return
                }
            }
        }
    }

    /**
     * 帧订阅三态：
     * - 有句柄在动（变换 / jitter / 倒放 / 淡变）→ 下一帧；
     * - 画面全静止、只等下一个锚点（如 static hold 等 onHoldExpired）→ 一次延时回调，不逐帧空转；
     * - 都没有 → 退订。
     *
     * 延时量只在一帧结束时（时间轴刚按本帧时刻推进过）计算；帧外的状态变化不知道「现在」，先要一帧再算。
     */
    private fun updateSubscription() {
        val animating = running.any { it.isAnimating() }
        val waitMs = if (animating) null else running.mapNotNull { it.msUntilNextAnchor() }.minOrNull()
        when {
            animating -> if (sub != Sub.IMMEDIATE) {
                if (sub == Sub.DELAYED) clock.removeFrameCallback(frameCallback)
                clock.postFrameCallback(frameCallback)
                sub = Sub.IMMEDIATE
            }
            waitMs != null -> if (sub == Sub.NONE) {
                if (settlingFrame) {
                    clock.postFrameCallbackDelayed(frameCallback, kotlin.math.ceil(waitMs).toLong())
                    sub = Sub.DELAYED
                } else {
                    clock.postFrameCallback(frameCallback)
                    sub = Sub.IMMEDIATE
                }
            }
            else -> if (sub != Sub.NONE) {
                clock.removeFrameCallback(frameCallback)
                sub = Sub.NONE
            }
        }
    }

    private fun onFrame(frameTimeNanos: Long) {
        // 帧回调是一次性的（Choreographer 语义），先标记未订阅，末尾按需重订
        sub = Sub.NONE
        for (h in running.toList()) {
            if (h.state == HandleState.ACTIVE) advanceActive(h, frameTimeNanos)
            if (h.state == HandleState.CANCELLING) advanceReverse(h, frameTimeNanos)
        }
        drainQueue()
        settlingFrame = true
        try {
            updateSubscription()
        } finally {
            settlingFrame = false
        }
    }

    private fun advanceActive(h: VisualHandle, now: Long) {
        val start = h.startNanos ?: now.also { h.startNanos = it }
        val t = (now - start) / 1_000_000.0
        val prev = h.timeMs
        h.timeMs = t
        h.render()
        h.notifyFrame()
        for (a in h.anchorsSorted) {
            if (a.at > prev && a.at <= t) {
                h.request.listener?.onAnchor(h, a.id)
                // 回调里 App 可能已 cancel：状态变了就停止分发本帧剩余锚点
                if (h.state != HandleState.ACTIVE) return
            }
        }
    }

    private fun advanceReverse(h: VisualHandle, now: Long) {
        val rs = h.reverseStartNanos ?: now.also { h.reverseStartNanos = it }
        val t = h.reverseOriginMs - (now - rs) / 1_000_000.0
        h.timeMs = maxOf(t, 0.0)
        h.render()
        h.notifyFrame()
        if (t <= 0.0) {
            h.endReason = h.reverseEndReason
            finish(h, HandleFsm.transition(h.state, HandleEvent.REVERSE_DONE)!!)
        }
    }

    internal fun ladderSource(handle: VisualHandle, level: Int): SampledSource {
        val ir = handle.request.ir
        val grid = ir.model.sampler.gridPx shl level
        val jitter = ir.model.sampler.jitterPx
        val prepared = handle.request.prepared?.ladder?.getOrNull(level)
        return if (prepared != null && prepared.seed == ir.model.seed && prepared.gridPx == grid && prepared.jitterPx == jitter) {
            prepared
        } else {
            ParticleField.sampleSource(ir.model.seed, grid, jitter, handle.request.source)
        }
    }
}

/**
 * 动效句柄：App 持有它控制收回。每个句柄一个状态机实例，互不影响；并发由粒子预算管，不由状态机管。
 */
public class VisualHandle internal constructor(
    public val id: Long,
    private val engine: VisualEngine,
    internal val request: PlayRequest,
) {
    public var state: HandleState = HandleState.IDLE
        private set

    /** 当前时间轴位置（ms）。reverse 期间递减。 */
    public var timeMs: Double = -1.0
        internal set

    /** 由 `phaseAt(t)` 派生——相位不是状态。 */
    public val phase: String? get() = phaseAt(request.ir.phases, timeMs)

    public var endReason: EndReason? = null
        internal set

    /** 实际使用的采样网格（降级后可能大于 IR 声明值）；减少动效时为 0。 */
    public var gridPx: Int = 0
        private set

    public val particleCount: Int get() = particleField?.size ?: 0

    /** 渲染器挂钩：每次该句柄的画面变化时调用（平台层用来 invalidate）。 */
    public var frameObserver: (() -> Unit)? = null

    public val isQueued: Boolean get() = queued

    /** 在等前一次（同渲染位前任 / 被挤者）收回完成。 */
    public val isAwaitingHandoff: Boolean get() = awaiting.isNotEmpty()

    /** 以 REJECTED 终结或 play 返回 Rejected 时的原因。 */
    public var rejectReason: RejectReason? = null
        internal set

    public val reducedMotion: Boolean get() = request.reducedMotion

    public fun cancel(mode: CancelMode): Boolean = engine.cancel(this, mode)

    /** 平台渲染失败时上报：active/cancelling → failed（动效失败要可见，不静默降级）。 */
    public fun reportError(cause: Throwable) {
        lastError = cause
        engine.reportError(this)
    }

    public var lastError: Throwable? = null
        private set

    public fun currentRender(): RenderInstruction = instruction

    // ---------------------------------------------------------------- internal

    internal val knobs = TimelineKnobs(request.ir)
    internal val anchorsSorted = request.ir.anchors.sortedBy { it.at }
    internal var queued = false
    internal var startNanos: Long? = null
    internal var reverseStartNanos: Long? = null
    internal var reverseOriginMs = 0.0
    internal var reverseEndReason = EndReason.REVERSED

    /** 测试钩子：入队序号（-1 = 从未排队），用于断言 QUEUE 严格 FIFO */
    internal var queueSeq = -1L

    /** 本句柄在等哪些句柄收回完成 / 哪些句柄在等本句柄 */
    internal val awaiting = LinkedHashSet<VisualHandle>()
    internal val waiters = ArrayList<VisualHandle>()

    internal fun awaitHandoff(prev: VisualHandle) {
        if (awaiting.add(prev)) prev.waiters += this
    }

    internal fun clearAwaiting() {
        for (a in awaiting) a.waiters.remove(this)
        awaiting.clear()
    }

    private val evaluator = TrajectoryEvaluator(request.ir)
    private val fieldCache = arrayOfNulls<ParticleField>(Admission.LADDER_STEPS)
    private var particleField: ParticleField? = null
    private val frame = ParticleFrame()
    private var instruction: RenderInstruction = RenderInstruction.None
    private var lastNotified: RenderInstruction? = null

    /** 测试钩子：是否仍持有任何粒子场（终态后必须为 false——库渲染完不留副本）。 */
    internal val holdsParticleData: Boolean get() = particleField != null || fieldCache.any { it != null }

    internal fun fieldAt(level: Int): ParticleField =
        fieldCache[level] ?: ParticleField.build(engine.ladderSource(this, level), request.target).also { fieldCache[level] = it }

    internal fun adopt(level: Int) {
        particleField = fieldAt(level)
        gridPx = particleField!!.gridPx
        fieldCache.fill(null)
    }

    internal fun setState(s: HandleState) {
        if (state == s) return
        state = s
        request.listener?.onStateChanged(this, s)
    }

    internal fun cost(): Int {
        val f = particleField ?: return 0
        return when (state) {
            HandleState.CANCELLING -> f.size
            HandleState.ACTIVE -> if (timeMs < knobs.morphEnd || knobs.holdMode == HoldMode.JITTER) f.size else 0
            else -> 0
        }
    }

    /** 画面在动：需要逐帧推进。 */
    internal fun isAnimating(): Boolean = when (state) {
        HandleState.CANCELLING -> true
        HandleState.ACTIVE -> timeMs < knobs.morphEnd || (knobs.holdMode == HoldMode.JITTER && !request.reducedMotion)
        else -> false
    }

    /** 画面静止时距下一个锚点还有多久（ms）；没有待发锚点返回 null。 */
    internal fun msUntilNextAnchor(): Double? {
        if (state != HandleState.ACTIVE) return null
        val next = anchorsSorted.firstOrNull { it.at > timeMs } ?: return null
        return next.at - timeMs
    }

    /** 未启动期间不持粒子场（排队 / 等交接），放行时重建。 */
    internal fun dropFieldCache() {
        fieldCache.fill(null)
    }

    /** play 失败回滚专用：静默进入终态，不回调监听器（监听器正是抛异常的一方，调用方也从未拿到句柄）。 */
    internal fun abandon() {
        releaseResources()
        if (!state.isTerminal) {
            endReason = EndReason.ERROR
            state = HandleState.FAILED
        }
    }

    internal fun render() {
        val t = timeMs
        instruction = when {
            request.reducedMotion -> RenderInstruction.Crossfade(knobs.morphAt(t).toFloat())
            state == HandleState.ACTIVE && t >= knobs.morphEnd && knobs.holdMode == HoldMode.STATIC -> {
                // 已停在最终画面：只需画 target，粒子场主动释放（reverse 时按需重建并重新过预算裁决）
                releaseParticles()
                RenderInstruction.StaticTarget
            }
            else -> {
                val f = particleField ?: return
                evaluator.fill(f, t, frame)
                RenderInstruction.Particles(frame)
            }
        }
    }

    /** 画面没变（static hold / 淡变结束后）就不打扰渲染器——static hold 的「≈0 开销」靠这里兑现。 */
    internal fun notifyFrame() {
        val cur = instruction
        if (cur !is RenderInstruction.Particles && cur == lastNotified) return
        lastNotified = cur
        frameObserver?.invoke()
    }

    /** 当前是否持有可直接倒放的粒子场（静态停留后为 false）。 */
    internal val hasParticles: Boolean get() = particleField != null

    private fun releaseParticles() {
        if (particleField == null) return
        particleField = null
        frame.clear()
    }

    internal fun releaseResources() {
        particleField = null
        fieldCache.fill(null)
        frame.clear()
        instruction = RenderInstruction.None
    }
}
