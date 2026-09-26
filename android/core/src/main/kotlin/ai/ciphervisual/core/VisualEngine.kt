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
)

/** 回调在帧时钟线程（Android 主线程）上发出；回调里可直接调 `handle.cancel(...)`。 */
public interface VisualListener {
    /** 到达 IR 锚点。App 在这里编排触觉/音效/埋点——库不订阅任何其他库。 */
    public fun onAnchor(handle: VisualHandle, anchorId: String) {}

    public fun onStateChanged(handle: VisualHandle, state: HandleState) {}
}

public sealed interface PlayResult {
    public data class Started(val handle: VisualHandle) : PlayResult

    /** `QUEUE` 策略下预算不足：handle 停在 idle，预算释放后自动启动；`cancel()` 撤出队列（→ completed / WITHDRAWN）。 */
    public data class Queued(val handle: VisualHandle) : PlayResult

    /** `DROP_NEWEST` 策略下预算不足 */
    public data object Rejected : PlayResult
}

/** 终态原因。 */
public enum class EndReason { TEARDOWN, REVERSED, EVICTED, ERROR, WITHDRAWN }

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
    private var nextId = 1L
    private var subscribed = false
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
        return when (val d = decide(handle)) {
            is AdmissionDecision.Admit -> {
                start(handle, d)
                PlayResult.Started(handle)
            }
            AdmissionDecision.Queue -> {
                handle.queued = true
                queue.addLast(handle)
                PlayResult.Queued(handle)
            }
            AdmissionDecision.Reject -> PlayResult.Rejected
        }
    }

    /** 销毁：teardown 全部句柄、清空队列、退订帧时钟。生命周期清理，不是收回策略。 */
    public fun release() {
        queue.clear()
        for (h in running.toList()) teardown(h, EndReason.TEARDOWN)
        updateSubscription()
    }

    // ---------------------------------------------------------------- 句柄控制（VisualHandle 转调）

    internal fun cancel(handle: VisualHandle, mode: CancelMode): Boolean {
        if (handle.queued) {
            // 排队中撤回：idle --withdraw--> completed，mode 无意义（尚未渲染过）
            queue.remove(handle)
            handle.queued = false
            handle.endReason = EndReason.WITHDRAWN
            handle.setState(HandleFsm.transition(handle.state, HandleEvent.WITHDRAW)!!)
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
                val next = HandleFsm.transition(handle.state, HandleEvent.CANCEL_REVERSE) ?: return false
                // 从 hold 收回时先跳回 target 队形（morphEnd），再沿时间轴倒放到 0
                handle.reverseOriginMs = minOf(handle.timeMs, handle.knobs.morphEnd)
                handle.reverseStartNanos = null
                handle.setState(next)
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

    private fun decide(handle: VisualHandle): AdmissionDecision {
        val running = running.map { RunningCost(it.id, it.cost()) }
        if (handle.request.reducedMotion) {
            return Admission.decide(config.overflowStrategy, config.particleBudget, running, listOf(0))
        }
        val usage = running.sumOf { it.cost }
        val c0 = handle.fieldAt(0).size
        val ladder = if (config.overflowStrategy == OverflowStrategy.DEGRADE && usage + c0 > config.particleBudget) {
            List(Admission.LADDER_STEPS) { handle.fieldAt(it).size }
        } else {
            listOf(c0)
        }
        return Admission.decide(config.overflowStrategy, config.particleBudget, running, ladder)
    }

    private fun start(handle: VisualHandle, decision: AdmissionDecision.Admit) {
        for (id in decision.evict) running.firstOrNull { it.id == id }?.let { teardown(it, EndReason.EVICTED) }
        handle.queued = false
        if (!handle.request.reducedMotion) handle.adopt(decision.gridLevel)
        running += handle
        handle.setState(HandleFsm.transition(handle.state, HandleEvent.PLAY)!!)
        updateSubscription()
    }

    private fun teardown(handle: VisualHandle, reason: EndReason) {
        handle.endReason = reason
        finish(handle, HandleState.COMPLETED)
    }

    private fun finish(handle: VisualHandle, terminal: HandleState) {
        running.remove(handle)
        handle.releaseResources()
        handle.setState(terminal)
        handle.notifyFrame()
    }

    private fun drainQueue() {
        while (queue.isNotEmpty()) {
            val head = queue.first()
            val d = decide(head)
            if (d !is AdmissionDecision.Admit) return
            queue.removeFirst()
            start(head, d)
        }
    }

    private fun updateSubscription() {
        val need = running.any { it.needsFrames() }
        if (need && !subscribed) {
            clock.postFrameCallback(frameCallback)
            subscribed = true
        } else if (!need && subscribed) {
            clock.removeFrameCallback(frameCallback)
            subscribed = false
        }
    }

    private fun onFrame(frameTimeNanos: Long) {
        // 帧回调是一次性的（Choreographer 语义），先标记未订阅，末尾按需重订
        subscribed = false
        for (h in running.toList()) {
            if (h.state == HandleState.ACTIVE) advanceActive(h, frameTimeNanos)
            if (h.state == HandleState.CANCELLING) advanceReverse(h, frameTimeNanos)
        }
        drainQueue()
        updateSubscription()
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
            h.endReason = EndReason.REVERSED
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

    private val evaluator = TrajectoryEvaluator(request.ir)
    private val fieldCache = arrayOfNulls<ParticleField>(Admission.LADDER_STEPS)
    private var particleField: ParticleField? = null
    private val frame = ParticleFrame()
    private var instruction: RenderInstruction = RenderInstruction.None
    private var lastNotified: RenderInstruction? = null

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

    internal fun needsFrames(): Boolean = when (state) {
        HandleState.CANCELLING -> true
        HandleState.ACTIVE -> timeMs < knobs.morphEnd ||
            (knobs.holdMode == HoldMode.JITTER && !request.reducedMotion) ||
            anchorsSorted.any { it.at > timeMs }
        else -> false
    }

    internal fun render() {
        val t = timeMs
        instruction = when {
            request.reducedMotion -> RenderInstruction.Crossfade(knobs.morphAt(t).toFloat())
            state == HandleState.ACTIVE && t >= knobs.morphEnd && knobs.holdMode == HoldMode.STATIC ->
                RenderInstruction.StaticTarget
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

    internal fun releaseResources() {
        particleField = null
        fieldCache.fill(null)
        frame.clear()
        instruction = RenderInstruction.None
    }
}
