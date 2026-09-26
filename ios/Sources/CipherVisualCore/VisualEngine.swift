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

    public init(ir: TimelineIR, source: PixelSource, target: PixelSource, listener: VisualListener? = nil,
                reducedMotion: Bool = false, prepared: PreparedSource? = nil) {
        self.ir = ir
        self.source = source
        self.target = target
        self.listener = listener
        self.reducedMotion = reducedMotion
        self.prepared = prepared
    }
}

public enum PlayResult {
    case started(VisualHandle)
    /// `queue` 策略下预算不足：handle 停在 idle，预算释放后自动启动；`cancel()` 撤出队列（→ completed / withdrawn）
    case queued(VisualHandle)
    /// `dropNewest` 策略下预算不足
    case rejected

    public var handle: VisualHandle? {
        switch self {
        case let .started(h), let .queued(h): return h
        case .rejected: return nil
        }
    }
}

public enum EndReason {
    case teardown, reversed, evicted, error, withdrawn
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

/// 动效引擎：时间轴推进 + 锚点分发 + 粒子预算。单线程约束（主线程）。库不自动收回。
public final class VisualEngine {
    public let config: EngineConfig
    private let clock: FrameClock
    private var running: [VisualHandle] = []
    private var queue: [VisualHandle] = []
    private var nextId: Int64 = 1
    private var subscribed = false
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
        switch decide(handle) {
        case let .admit(level, evict):
            start(handle, level: level, evict: evict)
            return .started(handle)
        case .queue:
            handle.queued = true
            queue.append(handle)
            return .queued(handle)
        case .reject:
            return .rejected
        }
    }

    /// 销毁：teardown 全部句柄、清空队列、退订帧时钟。
    public func release() {
        queue.removeAll()
        for h in running { teardown(h, .teardown) }
        updateSubscription()
    }

    // MARK: 句柄控制

    @discardableResult
    func cancel(_ handle: VisualHandle, _ mode: CancelMode) -> Bool {
        if handle.queued {
            // 排队中撤回：idle --withdraw--> completed，mode 无意义（尚未渲染过）
            queue.removeAll { $0 === handle }
            handle.queued = false
            handle.endReason = .withdrawn
            handle.setState(HandleFSM.transition(handle.state, .withdraw)!)
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
            guard let next = HandleFSM.transition(handle.state, .cancelReverse) else { return false }
            handle.reverseOriginMs = min(handle.timeMs, handle.knobs.morphEnd)
            handle.reverseStartNanos = nil
            handle.setState(next)
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

    private func decide(_ handle: VisualHandle) -> AdmissionDecision {
        let costs = running.map { RunningCost(id: $0.id, cost: $0.cost()) }
        if handle.request.reducedMotion {
            return Admission.decide(config.overflowStrategy, budget: config.particleBudget, running: costs, ladderCosts: [0])
        }
        let usage = costs.reduce(0) { $0 + $1.cost }
        let c0 = handle.fieldAt(0).count
        let ladder: [Int]
        if config.overflowStrategy == .degrade && usage + c0 > config.particleBudget {
            ladder = (0..<Admission.ladderSteps).map { handle.fieldAt($0).count }
        } else {
            ladder = [c0]
        }
        return Admission.decide(config.overflowStrategy, budget: config.particleBudget, running: costs, ladderCosts: ladder)
    }

    private func start(_ handle: VisualHandle, level: Int, evict: [Int64]) {
        for id in evict {
            if let h = running.first(where: { $0.id == id }) { teardown(h, .evicted) }
        }
        handle.queued = false
        if !handle.request.reducedMotion { handle.adopt(level) }
        running.append(handle)
        handle.setState(HandleFSM.transition(handle.state, .play)!)
        updateSubscription()
    }

    private func teardown(_ handle: VisualHandle, _ reason: EndReason) {
        handle.endReason = reason
        finish(handle, .completed)
    }

    private func finish(_ handle: VisualHandle, _ terminal: HandleState) {
        running.removeAll { $0 === handle }
        handle.releaseResources()
        handle.setState(terminal)
        handle.notifyFrame()
    }

    private func drainQueue() {
        while let head = queue.first {
            guard case let .admit(level, evict) = decide(head) else { return }
            queue.removeFirst()
            start(head, level: level, evict: evict)
        }
    }

    private func updateSubscription() {
        let need = running.contains { $0.needsFrames() }
        if need && !subscribed {
            clock.postFrameCallback(frameCallback)
            subscribed = true
        } else if !need && subscribed {
            clock.removeFrameCallback(frameCallback)
            subscribed = false
        }
    }

    private func onFrame(_ nanos: UInt64) {
        subscribed = false
        for h in running {
            if h.state == .active { advanceActive(h, nanos) }
            if h.state == .cancelling { advanceReverse(h, nanos) }
        }
        drainQueue()
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
            h.endReason = .reversed
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
    /// 实际采样网格（降级后可能大于 IR 声明值）；减少动效时为 0
    public private(set) var gridPx = 0
    public private(set) var lastError: Error?
    /// 渲染器挂钩：画面变化时调用（平台层用来 setNeedsDisplay）
    public var frameObserver: (() -> Void)?

    public var phase: String? { phaseAt(request.ir.phases, timeMs) }
    public var particleCount: Int { particleField?.count ?? 0 }
    public var isQueued: Bool { queued }
    public var reducedMotion: Bool { request.reducedMotion }

    let request: PlayRequest
    let knobs: TimelineKnobs
    let anchorsSorted: [AnchorSpec]
    var queued = false
    var startNanos: UInt64?
    var reverseStartNanos: UInt64?
    var reverseOriginMs = 0.0

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

    func needsFrames() -> Bool {
        switch state {
        case .cancelling: return true
        case .active:
            return timeMs < knobs.morphEnd
                || (knobs.holdMode == .jitter && !request.reducedMotion)
                || anchorsSorted.contains { $0.at > timeMs }
        default: return false
        }
    }

    func render() {
        let t = timeMs
        if request.reducedMotion {
            instruction = .crossfade(targetAlpha: Float(knobs.morphAt(t)))
        } else if state == .active && t >= knobs.morphEnd && knobs.holdMode == .static {
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

    func releaseResources() {
        particleField = nil
        fieldCache = Array(repeating: nil, count: Admission.ladderSteps)
        frame.clear()
        instruction = .none
    }
}
