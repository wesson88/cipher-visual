import Foundation

// MARK: - PRNG · mulberry32

/// 粒子场种子协议的 PRNG（spec/contracts.md §3）。双端逐位一致：同 seed → 同序列。
public struct Mulberry32 {
    public private(set) var state: UInt32

    public init(seed: UInt32) {
        state = seed
    }

    public mutating func nextUInt32() -> UInt32 {
        state = state &+ 0x6D2B_79F5
        var t = state
        t = (t ^ (t >> 15)) &* (t | 1)
        t = (t &+ ((t ^ (t >> 7)) &* (t | 61))) ^ t
        return t ^ (t >> 14)
    }

    /// [0, 1)
    public mutating func nextDouble() -> Double {
        Double(nextUInt32()) / 4_294_967_296.0
    }
}

// MARK: - 缓动

public enum Ease: String, CaseIterable, Codable {
    case linear
    case cubicIn
    case cubicOut
    case cubicInOut

    public func apply(_ p: Double) -> Double {
        switch self {
        case .linear:
            return p
        case .cubicIn:
            return p * p * p
        case .cubicOut:
            let q = 1 - p
            return 1 - q * q * q
        case .cubicInOut:
            if p < 0.5 { return 4 * p * p * p }
            let q = -2 * p + 2
            return 1 - q * q * q / 2
        }
    }
}

// MARK: - 原语

public struct PrimitiveSample: Equatable {
    public let value: Double
    public let done: Bool
}

/// 原语契约：时间→值的纯函数，无状态、不知道帧时钟、任意 t 可采样。
public protocol Primitive {
    func next(_ t: Double) -> PrimitiveSample
}

public struct KeyframePrimitive: Primitive {
    let times: [Double]
    let values: [Double]
    let ease: Ease

    public init(times: [Double], values: [Double], ease: Ease) {
        precondition(times.count >= 2 && times.count == values.count, "keyframe times/values 长度不合法")
        self.times = times
        self.values = values
        self.ease = ease
    }

    public init(spec: PrimitiveSpec) {
        guard let e = Ease(rawValue: spec.ease) else { preconditionFailure("unknown ease: \(spec.ease)") }
        self.init(times: spec.times, values: spec.values, ease: e)
    }

    public func next(_ t: Double) -> PrimitiveSample {
        let last = times.count - 1
        if t <= times[0] { return PrimitiveSample(value: values[0], done: false) }
        if t >= times[last] { return PrimitiveSample(value: values[last], done: true) }
        var i = 0
        while t >= times[i + 1] { i += 1 }
        let p = (t - times[i]) / (times[i + 1] - times[i])
        return PrimitiveSample(value: values[i] + (values[i + 1] - values[i]) * ease.apply(p), done: false)
    }
}

// MARK: - effect 原语 token

public enum ModelType: String {
    case particleField
    case transform
    case path
}

/// 视觉动作类型，不是业务场景（spec/effects.json）。
public enum Effect: String, CaseIterable {
    case dissolve, burst, fall, rise, impact, fade, slide, shake, pulse, scale, spiral, orbit

    public var model: ModelType {
        switch self {
        case .dissolve, .burst, .fall, .rise, .impact: return .particleField
        case .fade, .slide, .shake, .pulse, .scale: return .transform
        case .spiral, .orbit: return .path
        }
    }

    public var implemented: Bool { self == .dissolve }
}

public enum HoldMode: String {
    case `static`
    case jitter
}

public enum Anchors {
    public static let onBurst = "onBurst"
    public static let onResolve = "onResolve"
    public static let onHoldExpired = "onHoldExpired"
}
