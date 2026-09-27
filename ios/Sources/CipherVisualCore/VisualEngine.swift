import Foundation

public struct EngineConfig {
    public var hardwareTier: HardwareTier
    public var overflowStrategy: OverflowStrategy
    public var particleBudget: Int

    public init(hardwareTier: HardwareTier = .linearXFull, overflowStrategy: OverflowStrategy = .degrade, particleBudget: Int? = nil) {
        self.hardwareTier = hardwareTier
        self.overflowStrategy = overflowStrategy
        self.particleBudget = particleBudget ?? hardwareTier.defaultParticleBudget
    }
}

/// 回调在帧时钟线程（主线程）发出；回调里可直接 `handle.cancel(...)`。
public protocol VisualListener: AnyObject {
    /// 到达 IR 锚点。App 在这里编排触觉/音效/埋点——库不订阅任何其他库。
    func onAnchor(_ handle: VisualHandle, anchorId: String)
    func onStateChanged(_ handle: VisualHandle, state: HandleState)
}

public extension VisualListener {
    func onAnchor(_ handle: VisualHandle, anchorId: String) {}
    func onStateChanged(_ handle: VisualHandle, state: HandleState) {}
}

public final class PreparedSource {
    let source: PixelSource
    let ladder: [SampledSource]

    init(source: PixelSource, ladder: [SampledSource]) {
        self.source = source
        self.ladder = ladder
    }
}

public struct PlayRequest {
    public let ir: TimelineIR
    public let source: PixelSource
    public let target: PixelSource
    /// 弱引用（iOS 惯例，避免 VC ↔ handle 环）：调用方负责持有 listener
    public weak var listener: VisualListener?
    /// 系统「减少动效」开启：降级为淡入淡出，锚点时序不变
    public let reducedMotion: Bool
    public let prepared: PreparedSource?
    /// 渲染位（平台层传入 view）。同一渲染位上「最新的取消前一次」：前一次终态回调发出后新请求才启动。nil = 不参与互斥
    public let slot: AnyHashable?
    /// 取消前一次（同渲染位前任 / dropOldest 挤掉的）用哪种收回：默认 teardown 瞬时，可选 reverse
    public let preemptMode: CancelMode

    public init(ir: TimelineIR, source: PixelSource, target: PixelSource, listener: VisualListener? = nil,
                reducedMotion: Bool = false, prepared: PreparedSource? = nil,
                slot: AnyHashable? = nil, preemptMode: CancelMode = .teardown) {
        self.ir = ir
        self.source = source
        self.target = target
        self.listener = listener
        self.reducedMotion = reducedMotion
        self.prepared = prepared
        self.slot = slot
        self.preemptMode = preemptMode
    }
}

public enum PlayResult {
    case started(VisualHandle)
    /// 未立即启动，handle 停在 idle：`queue` 策略排队中，或在等前一次取消完成（取消交接）。
    /// 之后自动启动；也可能以 withdrawn / preempted / rejected 进入终态
    case queued(VisualHandle)
    /// 裁决拒绝，未产生句柄
    case rejected(RejectReason)

    public var handle: VisualHandle? {
        switch self {
        case let .started(h), let .queued(h): return h
        case .rejected: return nil
        }
    }
}

public enum EndReason {
    case teardown, reversed
    /// 被 dropOldest 挤掉
    case evicted
    case error
    /// 未启动即被 App 撤回
    case withdrawn
    /// 同渲染位上被更新的请求取代
    case preempted
    /// 等待交接后重新裁决被拒（原因见 `VisualHandle.rejectReason`）
    case rejected
}

public enum RenderInstruction: Equatable {
    case none
    case particles(ParticleFrame)
    case staticTarget
    case crossfade(targetAlpha: Float)

    public static func == (a: RenderInstruction, b: RenderInstruction) -> Bool {
        switch (a, b) {
        case (.none, .none), (.staticTarget, .staticTarget): return true
        case let (.particles(x), .particles(y)): return x === y
        case let (.crossfade(x), .crossfade(y)): return x == y
        default: return false
        }
    }
}

/// 动效引擎：时间轴推进 + 锚点分发 + 粒子预算 + 取消交接。单线程约束（主线程）。库不自动收回。
/// 结构与 Android `VisualEngine.kt` 一一对应，改动须双端同步。
public final class VisualEngine {
    public let config: EngineConfig
    private let clock: FrameClock
    private var running: [VisualHandle] = []
    private var queue: [VisualHandle] = []
    /// 渲染位 → 该位上所有未终态句柄。不能只记「最新一个」：最新的被撤回时，更早的可能仍在倒放
    private var slotMembers: [AnyHashable: [VisualHandle]] = [:]
    private var releasing = false
    private var nextQueueSeq: Int64 = 0
    private var nextId: Int64 = 1
    /// 帧订阅：none / immediate（下一帧）/ delayed（画面静止，只等下一个锚点）
    private enum Sub { case none, immediate, delayed }
    private var sub = Sub.none
    private var settlingFrame = false
    private lazy var frameCallback = FrameCallback { [weak self] nanos in self?.onFrame(nanos) }

    public init(clock: FrameClock, config: EngineConfig = EngineConfig()) {
        self.clock = clock
        self.config = config
    }

    public var particleUsage: Int { running.reduce(0) { $0 + $1.cost() } }
    public var activeHandles: [VisualHandle] { running }

    public func prepare(source: PixelSource, seed: UInt32, gridPx: Int, jitterPx: Int) -> PreparedSource {
        PreparedSource(source: source, ladder: (0..<Admission.ladderSteps).map {
            ParticleField.sampleSource(seed: seed, gridPx: gridPx << $0, jitterPx: jitterPx, source: source)
        })
    }

    public func play(_ request: PlayRequest) throws -> PlayResult {
        try IRValidator.requireValid(request.ir)
        let handle = VisualHandle(id: nextId, engine: self, request: request)
        nextId += 1
        if let slot = request.slot {
            // 同位所有未终态前任都由新请求取消，未即时终态的等它们交接；先撤回还没启动的，避免级联放行造成闪现
            let prevs = (slotMembers[slot] ?? []).sorted { a, b in a.state == .idle && b.state != .idle }
            for prev in prevs {
                preempt(prev, request.preemptMode, .preempted)
                if !prev.state.isTerminal { handle.awaitHandoff(prev) }
            }
            // 前任终态时会把空列表整个移出字典，这里必须重新取
            slotMembers[slot, default: []].append(handle)
        }
        let result: PlayResult
        if !handle.awaiting.isEmpty {
            result = .queued(handle)
        } else {
            drainQueue()
            result = admitOrWait(handle, announced: false)
        }
        updateSubscription()
        return result
    }

    /// 销毁：撤回全部未启动句柄、teardown 全部运行句柄、退订帧时钟。
    public func release() {
        releasing = true
        defer {
            // 与 Android 的 try/finally 同构：无论如何都不能让引擎卡在 releasing
            slotMembers.removeAll()
            releasing = false
            updateSubscription()
        }
        var pending: [VisualHandle] = queue
        for h in running.flatMap({ $0.waiters }) where !pending.contains(where: { $0 === h }) { pending.append(h) }
        for h in pending { withdraw(h, .withdrawn) }
        for h in running { teardown(h, .teardown) }
    }

    // MARK: 句柄控制

    @discardableResult
    func cancel(_ handle: VisualHandle, _ mode: CancelMode) -> Bool {
        if handle.state == .idle {
            withdraw(handle, .withdrawn)
            drainQueue()
            updateSubscription()
            return true
        }
        switch mode {
        case .teardown:
            guard HandleFSM.transition(handle.state, .cancelTeardown) != nil else { return false }
            teardown(handle, .teardown)
            drainQueue()
            updateSubscription()
            return true
        case .reverse:
            guard HandleFSM.transition(handle.state, .cancelReverse) != nil else { return false }
            // 预算放不下重建时降为 teardown：腾出的预算可能放行排队者
            if !beginReverse(handle, .reversed) { drainQueue() }
            updateSubscription()
            return true
        }
    }

    func reportError(_ handle: VisualHandle) {
        guard HandleFSM.transition(handle.state, .error) != nil else { return }
        handle.endReason = .error
        finish(handle, .failed)
        drainQueue()
        updateSubscription()
    }

    // MARK: 内部

    /// - Parameter ahead: 排在它前面的排队数（新请求 / 交接后放行 = 当前队长；队头出队 = 0）
    private func decide(_ handle: VisualHandle, ahead: Int) -> AdmissionDecision {
        // 等交接 / 排队期间宿主可能已释放内容（view.detach）：不可读就拒绝，不去建空粒子场（双端同口径）
        guard handle.request.source.isAvailable, handle.request.target.isAvailable else {
            return .reject(.contentUnavailable)
        }
        let costs = running.map { RunningCost(id: $0.id, cost: $0.cost()) }
        if handle.request.reducedMotion {
            return Admission.decide(config.overflowStrategy, budget: config.particleBudget, running: costs, ladderCosts: [0], queued: ahead)
        }
        let usage = costs.reduce(0) { $0 + $1.cost }
        let c0 = handle.fieldAt(0).count
        let ladder: [Int]
        if config.overflowStrategy == .degrade && usage + c0 > config.particleBudget {
            ladder = (0..<Admission.ladderSteps).map { handle.fieldAt($0).count }
        } else {
            ladder = [c0]
        }
        return Admission.decide(config.overflowStrategy, budget: config.particleBudget, running: costs, ladderCosts: ladder, queued: ahead)
    }

    /// 裁决并启动；挤占且被挤者走 reverse 时进入等待（交接完成后由 `finish` 重新裁决）。
    private func admitOrWait(_ handle: VisualHandle, announced: Bool) -> PlayResult {
        switch decide(handle, ahead: queue.count) {
        case let .admit(level, evict):
            for id in evict {
                guard let victim = running.first(where: { $0.id == id }) else { continue }
                preempt(victim, handle.request.preemptMode, .evicted)
                if !victim.state.isTerminal { handle.awaitHandoff(victim) }
            }
            if !handle.awaiting.isEmpty {
                handle.dropFieldCache() // 等交接期间不持粒子场，放行时重建
                return .queued(handle)
            }
            // 挤占触发的 finish 会级联放行等交接的句柄，可能已吃掉腾出的预算：重新裁决
            if !evict.isEmpty { return admitOrWait(handle, announced: announced) }
            start(handle, level)
            return .started(handle)
        case .queue:
            handle.queued = true
            handle.queueSeq = nextQueueSeq
            nextQueueSeq += 1
            queue.append(handle)
            handle.dropFieldCache() // 排队期间不持粒子场（只为判超总预算才建的），出队时重建
            return .queued(handle)
        case let .reject(reason):
            handle.rejectReason = reason
            if announced {
                withdraw(handle, .rejected)
            } else {
                removeFromSlot(handle)
                handle.releaseResources()
            }
            return .rejected(reason)
        }
    }

    private func start(_ handle: VisualHandle, _ level: Int) {
        handle.queued = false
        if !handle.request.reducedMotion { handle.adopt(level) }
        running.append(handle)
        handle.setState(HandleFSM.transition(handle.state, .play)!)
        updateSubscription()
    }

    /// 取消前一次：未启动的直接撤回；teardown 瞬时终态；reverse 进入倒放（已在倒放的保持）。
    private func preempt(_ h: VisualHandle, _ mode: CancelMode, _ reason: EndReason) {
        if h.state == .idle {
            withdraw(h, reason)
        } else if mode == .teardown {
            teardown(h, reason)
        } else if h.state == .active {
            _ = beginReverse(h, reason)
        }
    }

    /// 发起 reverse。静态停留时粒子场已释放，须重建并过裁决：按 grid 阶梯取第一档放得下的（与打满策略无关）；
    /// 最粗一档仍放不下降为 teardown（主动收回记 teardown 而非 reversed，App 可分辨）。镜像 Android `beginReverse`。
    /// - Returns: true = 进入倒放；false = 已降为 teardown（句柄已终态）
    private func beginReverse(_ h: VisualHandle, _ reason: EndReason) -> Bool {
        if !h.request.reducedMotion && !h.hasParticles {
            guard let level = reverseLevel(h) else {
                teardown(h, reason == .reversed ? .teardown : reason)
                return false
            }
            h.adopt(level)
        }
        startReverse(h, reason)
        return true
    }

    private func reverseLevel(_ h: VisualHandle) -> Int? {
        // 内容已被宿主释放：不能重建倒放，按放不下处理（降为 teardown）
        guard h.request.source.isAvailable, h.request.target.isAvailable else { return nil }
        let usage = particleUsage
        for level in 0..<Admission.ladderSteps where usage + h.fieldAt(level).count <= config.particleBudget {
            return level
        }
        return nil
    }

    private func startReverse(_ h: VisualHandle, _ reason: EndReason) {
        h.reverseOriginMs = min(h.timeMs, h.knobs.morphEnd)
        h.reverseStartNanos = nil
        h.reverseEndReason = reason
        h.setState(HandleFSM.transition(h.state, .cancelReverse)!)
    }

    /// 未启动即退出：idle --withdraw--> completed，走统一收尾。
    private func withdraw(_ handle: VisualHandle, _ reason: EndReason) {
        queue.removeAll { $0 === handle }
        handle.queued = false
        handle.clearAwaiting()
        handle.endReason = reason
        finish(handle, HandleFSM.transition(handle.state, .withdraw)!)
    }

    private func teardown(_ handle: VisualHandle, _ reason: EndReason) {
        handle.endReason = reason
        finish(handle, .completed)
    }

    /// 统一收尾。终态回调发出之后才放行等它交接的句柄——「前一次拿到回调后再发起最新的」。
    private func finish(_ handle: VisualHandle, _ terminal: HandleState) {
        running.removeAll { $0 === handle }
        handle.releaseResources()
        handle.setState(terminal)
        handle.notifyFrame()
        removeFromSlot(handle)
        let waiters = handle.waiters
        handle.waiters.removeAll()
        for w in waiters {
            w.awaiting.removeAll { $0 === handle }
            if w.awaiting.isEmpty && w.state == .idle && !releasing { _ = admitOrWait(w, announced: true) }
        }
    }

    private func removeFromSlot(_ handle: VisualHandle) {
        guard let slot = handle.request.slot, var members = slotMembers[slot] else { return }
        members.removeAll { $0 === handle }
        slotMembers[slot] = members.isEmpty ? nil : members
    }

    private func drainQueue() {
        while let head = queue.first {
            switch decide(head, ahead: 0) {
            case let .admit(level, _):
                queue.removeFirst()
                start(head, level)
            case let .reject(reason):
                head.rejectReason = reason
                withdraw(head, .rejected)
            case .queue:
                head.dropFieldCache()
                return
            }
        }
    }

    /// 帧订阅三态，镜像 Android：有句柄在动 → 下一帧；全静止只等锚点 → 一次延时回调；都没有 → 退订。
    /// 延时量只在一帧结束时计算；帧外的状态变化不知道「现在」，先要一帧再算。
    private func updateSubscription() {
        let animating = running.contains { $0.isAnimating() }
        let waitMs = animating ? nil : running.compactMap { $0.msUntilNextAnchor() }.min()
        if animating {
            if sub != .immediate {
                if sub == .delayed { clock.removeFrameCallback(frameCallback) }
                clock.postFrameCallback(frameCallback)
                sub = .immediate
            }
        } else if let wait = waitMs {
            if sub == .none {
                if settlingFrame {
                    clock.postFrameCallback(frameCallback, delayMs: wait.rounded(.up))
                    sub = .delayed
                } else {
                    clock.postFrameCallback(frameCallback)
                    sub = .immediate
                }
            }
        } else if sub != .none {
            clock.removeFrameCallback(frameCallback)
            sub = .none
        }
    }

    private func onFrame(_ nanos: UInt64) {
        sub = .none
        for h in running {
            if h.state == .active { advanceActive(h, nanos) }
            if h.state == .cancelling { advanceReverse(h, nanos) }
        }
        drainQueue()
        settlingFrame = true
        defer { settlingFrame = false }
        updateSubscription()
    }

    private func advanceActive(_ h: VisualHandle, _ now: UInt64) {
        let start = h.startNanos ?? now
        h.startNanos = start
        let t = Double(now - start) / 1_000_000
        let prev = h.timeMs
        h.timeMs = t
        h.render()
        h.notifyFrame()
        for a in h.anchorsSorted where a.at > prev && a.at <= t {
            h.request.listener?.onAnchor(h, anchorId: a.id)
            if h.state != .active { return }
        }
    }

    private func advanceReverse(_ h: VisualHandle, _ now: UInt64) {
        let rs = h.reverseStartNanos ?? now
        h.reverseStartNanos = rs
        let t = h.reverseOriginMs - Double(now - rs) / 1_000_000
        h.timeMs = max(t, 0)
        h.render()
        h.notifyFrame()
        if t <= 0 {
            h.endReason = h.reverseEndReason
            finish(h, HandleFSM.transition(h.state, .reverseDone)!)
        }
    }

    func ladderSource(_ handle: VisualHandle, _ level: Int) -> SampledSource {
        let ir = handle.request.ir
        let grid = ir.model.sampler.gridPx << level
        let jitter = ir.model.sampler.jitterPx
        let seed = UInt32(truncatingIfNeeded: ir.model.seed)
        if let ladder = handle.request.prepared?.ladder, level < ladder.count {
            let p = ladder[level]
            if p.seed == seed && p.gridPx == grid && p.jitterPx == jitter { return p }
        }
        return ParticleField.sampleSource(seed: seed, gridPx: grid, jitterPx: jitter, source: handle.request.source)
    }
}

/// 动效句柄：App 持有它控制收回。每个句柄一个状态机实例，互不影响。
public final class VisualHandle {
    public let id: Int64
    public private(set) var state: HandleState = .idle
    /// 当前时间轴位置（ms），reverse 期间递减
    public internal(set) var timeMs: Double = -1
    public internal(set) var endReason: EndReason?
    /// 以 rejected 终结或 play 返回 rejected 时的原因
    public internal(set) var rejectReason: RejectReason?
    /// 实际采样网格（降级后可能大于 IR 声明值）；减少动效时为 0
    public private(set) var gridPx = 0
    public private(set) var lastError: Error?
    /// 渲染器挂钩：画面变化时调用（平台层用来 setNeedsDisplay）
    public var frameObserver: (() -> Void)?

    public var phase: String? { phaseAt(request.ir.phases, timeMs) }
    public var particleCount: Int { particleField?.count ?? 0 }
    public var isQueued: Bool { queued }
    /// 在等前一次（同渲染位前任 / 被挤者）收回完成
    public var isAwaitingHandoff: Bool { !awaiting.isEmpty }
    public var reducedMotion: Bool { request.reducedMotion }

    let request: PlayRequest
    let knobs: TimelineKnobs
    let anchorsSorted: [AnchorSpec]
    var queued = false
    var startNanos: UInt64?
    var reverseStartNanos: UInt64?
    var reverseOriginMs = 0.0
    var reverseEndReason: EndReason = .reversed
    /// 测试钩子：入队序号（-1 = 从未排队），用于断言 queue 严格 FIFO
    var queueSeq: Int64 = -1
    /// 本句柄在等哪些句柄收回完成 / 哪些句柄在等本句柄
    var awaiting: [VisualHandle] = []
    var waiters: [VisualHandle] = []

    private weak var engine: VisualEngine?
    private let evaluator: TrajectoryEvaluator
    private var fieldCache: [ParticleField?] = Array(repeating: nil, count: Admission.ladderSteps)
    private var particleField: ParticleField?
    private let frame = ParticleFrame()
    private var instruction: RenderInstruction = .none
    private var lastNotified: RenderInstruction?

    init(id: Int64, engine: VisualEngine, request: PlayRequest) {
        self.id = id
        self.engine = engine
        self.request = request
        knobs = TimelineKnobs(request.ir)
        anchorsSorted = request.ir.anchors.enumerated().sorted {
            $0.element.at != $1.element.at ? $0.element.at < $1.element.at : $0.offset < $1.offset
        }.map { $0.element }
        evaluator = TrajectoryEvaluator(request.ir)
    }

    @discardableResult
    public func cancel(_ mode: CancelMode) -> Bool { engine?.cancel(self, mode) ?? false }

    /// 平台渲染失败时上报：→ failed（动效失败要可见）。
    public func reportError(_ error: Error) {
        lastError = error
        engine?.reportError(self)
    }

    public func currentRender() -> RenderInstruction { instruction }

    // MARK: internal

    func awaitHandoff(_ prev: VisualHandle) {
        guard !awaiting.contains(where: { $0 === prev }) else { return }
        awaiting.append(prev)
        prev.waiters.append(self)
    }

    func clearAwaiting() {
        for a in awaiting { a.waiters.removeAll { $0 === self } }
        awaiting.removeAll()
    }

    /// 测试钩子：是否仍持有任何粒子场（终态后必须为 false——库渲染完不留副本）。
    var holdsParticleData: Bool { particleField != nil || fieldCache.contains { $0 != nil } }

    func fieldAt(_ level: Int) -> ParticleField {
        if let f = fieldCache[level] { return f }
        let src = engine?.ladderSource(self, level) ?? ParticleField.sampleSource(
            seed: UInt32(truncatingIfNeeded: request.ir.model.seed), gridPx: request.ir.model.sampler.gridPx << level,
            jitterPx: request.ir.model.sampler.jitterPx, source: request.source)
        let f = ParticleField.build(src, target: request.target)
        fieldCache[level] = f
        return f
    }

    func adopt(_ level: Int) {
        let f = fieldAt(level)
        particleField = f
        gridPx = f.gridPx
        fieldCache = Array(repeating: nil, count: Admission.ladderSteps)
    }

    func setState(_ s: HandleState) {
        guard state != s else { return }
        state = s
        request.listener?.onStateChanged(self, state: s)
    }

    func cost() -> Int {
        guard let f = particleField else { return 0 }
        switch state {
        case .cancelling: return f.count
        case .active: return (timeMs < knobs.morphEnd || knobs.holdMode == .jitter) ? f.count : 0
        default: return 0
        }
    }

    /// 画面在动：需要逐帧推进
    func isAnimating() -> Bool {
        switch state {
        case .cancelling: return true
        case .active: return timeMs < knobs.morphEnd || (knobs.holdMode == .jitter && !request.reducedMotion)
        default: return false
        }
    }

    /// 画面静止时距下一个锚点还有多久（ms）；没有待发锚点返回 nil
    func msUntilNextAnchor() -> Double? {
        guard state == .active, let next = anchorsSorted.first(where: { $0.at > timeMs }) else { return nil }
        return next.at - timeMs
    }

    /// 未启动期间不持粒子场（排队 / 等交接），放行时重建
    func dropFieldCache() {
        fieldCache = Array(repeating: nil, count: Admission.ladderSteps)
    }

    func render() {
        let t = timeMs
        if request.reducedMotion {
            instruction = .crossfade(targetAlpha: Float(knobs.morphAt(t)))
        } else if state == .active && t >= knobs.morphEnd && knobs.holdMode == .static {
            // 已停在最终画面：只需画 target，粒子场主动释放（reverse 时按需重建并重新过预算裁决）
            releaseParticles()
            instruction = .staticTarget
        } else if let f = particleField {
            evaluator.fill(f, t, into: frame)
            instruction = .particles(frame)
        }
    }

    /// 画面没变（static hold / 淡变结束后）就不打扰渲染器。
    func notifyFrame() {
        let cur = instruction
        if case .particles = cur {} else if cur == lastNotified { return }
        lastNotified = cur
        frameObserver?()
    }

    /// 当前是否持有可直接倒放的粒子场（静态停留后为 false）
    var hasParticles: Bool { particleField != nil }

    private func releaseParticles() {
        guard particleField != nil else { return }
        particleField = nil
        frame.clear()
    }

    func releaseResources() {
        particleField = nil
        fieldCache = Array(repeating: nil, count: Admission.ladderSteps)
        frame.clear()
        instruction = .none
    }
}
