import Foundation

/// 句柄状态（5 态）。active 内部的变换 / hold 由 `phaseAt(t)` 派生，不是子状态。
public enum HandleState: String, CaseIterable {
    case idle, active, cancelling, completed, failed

    public var isTerminal: Bool { self == .completed || self == .failed }
}

/// 句柄事件（4 事件；cancel 按 mode 拆成两个 wire 名）。
public enum HandleEvent: String, CaseIterable {
    case play, cancelTeardown, cancelReverse, reverseDone, error
}

/// `teardown` 瞬时清除（安全优先）| `reverse` 反向播放收回（体验优先）。选哪种归 App。
public enum CancelMode {
    case teardown
    case reverse
}

/// 转移表（spec/transitions.json）。非法组合返回 nil。
public enum HandleFSM {
    public static func transition(_ state: HandleState, _ event: HandleEvent) -> HandleState? {
        switch (state, event) {
        case (.idle, .play): return .active
        case (.active, .cancelTeardown): return .completed
        case (.active, .cancelReverse): return .cancelling
        case (.active, .error): return .failed
        case (.cancelling, .reverseDone): return .completed
        case (.cancelling, .cancelTeardown): return .completed
        case (.cancelling, .error): return .failed
        default: return nil
        }
    }
}

// MARK: - 预算

/// 硬件档——复用 CipherHaptic 已探定的档位结论，库不重新探测。⚠️ 预算默认值待真机标定。
public enum HardwareTier: String, CaseIterable {
    case linearXFull = "LINEAR_X_FULL"
    case ermZ = "ERM_Z"

    public var defaultParticleBudget: Int {
        switch self {
        case .linearXFull: return 8000
        case .ermZ: return 3000
        }
    }
}

/// 打满策略：选项归库、选择归外部、执行归库。不选时 `degrade` 兜底。
public enum OverflowStrategy: String, CaseIterable {
    case degrade, dropFrame, queue, dropNewest, dropOldest
}

public enum AdmissionDecision: Equatable {
    case admit(gridLevel: Int, evict: [Int64])
    case queue
    case reject
}

public struct RunningCost {
    public let id: Int64
    public let cost: Int

    public init(id: Int64, cost: Int) {
        self.id = id
        self.cost = cost
    }
}

/// 预算裁决，纯函数（contracts/golden/admission.json）。
public enum Admission {
    public static let ladderSteps = 3

    public static func decide(_ strategy: OverflowStrategy, budget: Int, running: [RunningCost], ladderCosts: [Int]) -> AdmissionDecision {
        let usage = running.reduce(0) { $0 + $1.cost }
        func fits(_ c: Int, _ u: Int) -> Bool { u + c <= budget }
        switch strategy {
        case .degrade:
            let level = ladderCosts.firstIndex { fits($0, usage) } ?? (ladderCosts.count - 1)
            return .admit(gridLevel: level, evict: [])
        case .dropFrame:
            return .admit(gridLevel: 0, evict: [])
        case .queue:
            return fits(ladderCosts[0], usage) ? .admit(gridLevel: 0, evict: []) : .queue
        case .dropNewest:
            return fits(ladderCosts[0], usage) ? .admit(gridLevel: 0, evict: []) : .reject
        case .dropOldest:
            var evict: [Int64] = []
            var u = usage
            for r in running {
                if fits(ladderCosts[0], u) { break }
                if r.cost == 0 { continue }
                evict.append(r.id)
                u -= r.cost
            }
            return .admit(gridLevel: 0, evict: evict)
        }
    }
}

// MARK: - 帧时钟

/// 帧时钟契约——与 CipherHaptic 唯一的共享面。iOS 实现 = CADisplayLink。
public protocol FrameClock: AnyObject {
    func postFrameCallback(_ callback: FrameCallback)
    func removeFrameCallback(_ callback: FrameCallback)
}

/// 一次性帧回调（引用语义，以对象身份增删）。
public final class FrameCallback {
    public let doFrame: (_ frameTimeNanos: UInt64) -> Void

    public init(_ doFrame: @escaping (_ frameTimeNanos: UInt64) -> Void) {
        self.doFrame = doFrame
    }
}
