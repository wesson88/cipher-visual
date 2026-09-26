import Foundation

/// Timeline IR（schema_version 1），JSON 键名同 vault IR SSOT §八 的 yaml。
public struct TimelineIR: Codable, Equatable {
    public var schemaVersion: Int
    public var effect: String
    public var model: ModelSpec
    public var phases: [PhaseSpec]
    public var trajectory: TrajectorySpec
    public var primitives: [String: PrimitiveSpec]
    public var anchors: [AnchorSpec]

    public static let currentSchemaVersion = 1

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case effect, model, phases, trajectory, primitives, anchors
    }

    public init(schemaVersion: Int, effect: String, model: ModelSpec, phases: [PhaseSpec],
                trajectory: TrajectorySpec, primitives: [String: PrimitiveSpec], anchors: [AnchorSpec]) {
        self.schemaVersion = schemaVersion
        self.effect = effect
        self.model = model
        self.phases = phases
        self.trajectory = trajectory
        self.primitives = primitives
        self.anchors = anchors
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try c.decode(Int.self, forKey: .schemaVersion)
        effect = try c.decode(String.self, forKey: .effect)
        model = try c.decode(ModelSpec.self, forKey: .model)
        phases = try c.decode([PhaseSpec].self, forKey: .phases)
        trajectory = try c.decode(TrajectorySpec.self, forKey: .trajectory)
        primitives = try c.decode([String: PrimitiveSpec].self, forKey: .primitives)
        anchors = try c.decodeIfPresent([AnchorSpec].self, forKey: .anchors) ?? []
    }

    /// 仅结构解析；语义校验走 `IRValidator`。
    public static func parse(_ json: Data) throws -> TimelineIR {
        try JSONDecoder().decode(TimelineIR.self, from: json)
    }

    public func toJSON() throws -> Data {
        try JSONEncoder().encode(self)
    }
}

public struct ModelSpec: Codable, Equatable {
    public var type: String
    public var sampler: SamplerSpec
    /// uint32（用 Int64 承载，以便校验越界值）
    public var seed: Int64
}

public struct SamplerSpec: Codable, Equatable {
    public var gridPx: Int
    public var jitterPx: Int

    enum CodingKeys: String, CodingKey {
        case gridPx = "grid_px"
        case jitterPx = "jitter_px"
    }
}

public struct PhaseSpec: Codable, Equatable {
    public var id: String
    public var range: [Double]
    public var mode: String?

    public var start: Double { range[0] }
    public var end: Double { range[1] }

    public init(id: String, range: [Double], mode: String? = nil) {
        self.id = id
        self.range = range
        self.mode = mode
    }
}

public struct TrajectorySpec: Codable, Equatable {
    public var physics: PhysicsSpec
    public var driver: String
}

public struct PhysicsSpec: Codable, Equatable {
    public var v0: Double
    public var drag: Double
    public var gravity: Double
}

public struct PrimitiveSpec: Codable, Equatable {
    public var type: String
    public var times: [Double]
    public var values: [Double]
    public var ease: String

    public init(type: String, times: [Double], values: [Double], ease: String) {
        self.type = type
        self.times = times
        self.values = values
        self.ease = ease
    }

    enum CodingKeys: String, CodingKey { case type, times, values, ease }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = try c.decode(String.self, forKey: .type)
        times = try c.decodeIfPresent([Double].self, forKey: .times) ?? []
        values = try c.decodeIfPresent([Double].self, forKey: .values) ?? []
        ease = try c.decodeIfPresent(String.self, forKey: .ease) ?? Ease.linear.rawValue
    }
}

public struct AnchorSpec: Codable, Equatable {
    public var id: String
    public var at: Double
}

// MARK: - 模板

/// effect → 默认 Timeline 模板。`holdMs` 是 hold 相位**时长**：hold = [600, 600 + holdMs]。
public enum IRTemplates {
    public static let defaultSeed: UInt32 = 0x3F2A_1C7B
    public static let defaultGridPx = 4
    public static let defaultJitterPx = 1

    public static func dissolve(holdMs: Double, holdMode: HoldMode = .static, seed: UInt32 = defaultSeed,
                                gridPx: Int = defaultGridPx, jitterPx: Int = defaultJitterPx) -> TimelineIR {
        precondition(holdMs > 0, "holdMs 必须 > 0")
        let holdEnd = 600 + holdMs
        return TimelineIR(
            schemaVersion: TimelineIR.currentSchemaVersion,
            effect: Effect.dissolve.rawValue,
            model: ModelSpec(type: ModelType.particleField.rawValue,
                             sampler: SamplerSpec(gridPx: gridPx, jitterPx: jitterPx),
                             seed: Int64(seed)),
            phases: [
                PhaseSpec(id: "burst", range: [0, 150]),
                PhaseSpec(id: "dissolve", range: [150, 350]),
                PhaseSpec(id: "resolve", range: [350, 600]),
                PhaseSpec(id: "hold", range: [600, holdEnd], mode: holdMode.rawValue),
            ],
            trajectory: TrajectorySpec(physics: PhysicsSpec(v0: 300, drag: 4, gravity: 200), driver: "prim_burst"),
            primitives: ["prim_burst": PrimitiveSpec(type: "keyframe", times: [0, 150, 600], values: [0, 1, 0],
                                                     ease: Ease.cubicOut.rawValue)],
            anchors: [
                AnchorSpec(id: Anchors.onBurst, at: 0),
                AnchorSpec(id: Anchors.onResolve, at: 600),
                AnchorSpec(id: Anchors.onHoldExpired, at: holdEnd),
            ]
        )
    }
}

// MARK: - 相位

/// 相位是纯函数不是状态：左闭右开，最后一个相位右端闭合；区间外 nil。
public func phaseAt(_ phases: [PhaseSpec], _ t: Double) -> String? {
    for (i, p) in phases.enumerated() {
        let isLast = i == phases.count - 1
        if t >= p.start && (t < p.end || (isLast && t == p.end)) { return p.id }
    }
    return nil
}

public struct TimelineKnobs {
    public let morphStart: Double
    public let morphEnd: Double
    public let holdEnd: Double
    public let holdMode: HoldMode

    public init(_ ir: TimelineIR) {
        let burst = ir.phases.first { $0.id == "burst" }!
        let hold = ir.phases.first { $0.id == "hold" }!
        morphStart = burst.end
        morphEnd = hold.start
        holdEnd = hold.end
        holdMode = HoldMode(rawValue: hold.mode ?? "") ?? .static
    }

    public func morphAt(_ t: Double) -> Double {
        if t <= morphStart { return 0 }
        if t >= morphEnd { return 1 }
        return Ease.cubicInOut.apply((t - morphStart) / (morphEnd - morphStart))
    }
}

// MARK: - 校验

public enum IRError: String, CaseIterable, Error {
    case SCHEMA_VERSION, EFFECT_UNKNOWN, EFFECT_UNSUPPORTED, MODEL_TYPE, SAMPLER_GRID, SAMPLER_JITTER, SEED
    case PHASES_EMPTY, PHASE_ID_DUP, PHASE_RANGE, PHASE_START, PHASE_GAP, PHASE_MODE_SCOPE, PHASE_MODE, PHASE_SET
    case PHYSICS, DRIVER_REF, PRIM_TYPE, PRIM_SHAPE, PRIM_TIMES, PRIM_EASE, ANCHOR_ID_DUP, ANCHOR_RANGE
}

public struct IRInvalid: Error {
    public let errors: Set<IRError>

    public init(errors: Set<IRError>) {
        self.errors = errors
    }
}

public enum IRValidator {
    private static let dissolvePhases = ["burst", "dissolve", "resolve", "hold"]

    public static func validate(_ ir: TimelineIR) -> Set<IRError> {
        var errs = Set<IRError>()
        if ir.schemaVersion != TimelineIR.currentSchemaVersion { errs.insert(.SCHEMA_VERSION) }
        let effect = Effect(rawValue: ir.effect)
        if effect == nil { errs.insert(.EFFECT_UNKNOWN) } else if !effect!.implemented { errs.insert(.EFFECT_UNSUPPORTED) }

        if ir.model.type != ModelType.particleField.rawValue { errs.insert(.MODEL_TYPE) }
        let g = ir.model.sampler.gridPx
        let j = ir.model.sampler.jitterPx
        if g < 1 { errs.insert(.SAMPLER_GRID) }
        if j < 0 || j >= g { errs.insert(.SAMPLER_JITTER) }
        if ir.model.seed < 0 || ir.model.seed > 0xFFFF_FFFF { errs.insert(.SEED) }

        let phases = ir.phases
        if phases.isEmpty { errs.insert(.PHASES_EMPTY) }
        let ids = phases.map { $0.id }
        if Set(ids).count != ids.count { errs.insert(.PHASE_ID_DUP) }
        for (i, p) in phases.enumerated() {
            if p.range.count != 2 || !(p.start < p.end) { errs.insert(.PHASE_RANGE) }
            if i == 0 && p.start != 0 { errs.insert(.PHASE_START) }
            if i > 0 && phases[i - 1].end != p.start { errs.insert(.PHASE_GAP) }
            if let mode = p.mode {
                if p.id != "hold" { errs.insert(.PHASE_MODE_SCOPE) } else if HoldMode(rawValue: mode) == nil { errs.insert(.PHASE_MODE) }
            }
        }
        if effect == .dissolve && ids != dissolvePhases { errs.insert(.PHASE_SET) }

        let phy = ir.trajectory.physics
        if !(phy.v0 >= 0) || !(phy.drag > 0) || !phy.gravity.isFinite { errs.insert(.PHYSICS) }
        if ir.primitives[ir.trajectory.driver] == nil { errs.insert(.DRIVER_REF) }
        for p in ir.primitives.values {
            if p.type != "keyframe" {
                errs.insert(.PRIM_TYPE)
                continue
            }
            if p.times.count < 2 || p.times.count != p.values.count {
                errs.insert(.PRIM_SHAPE)
            } else if (1..<p.times.count).contains(where: { !(p.times[$0] > p.times[$0 - 1]) }) {
                errs.insert(.PRIM_TIMES)
            }
            if Ease(rawValue: p.ease) == nil { errs.insert(.PRIM_EASE) }
        }

        let end = phases.last?.end ?? 0
        let aids = ir.anchors.map { $0.id }
        if Set(aids).count != aids.count { errs.insert(.ANCHOR_ID_DUP) }
        if ir.anchors.contains(where: { !($0.at >= 0 && $0.at <= end) }) { errs.insert(.ANCHOR_RANGE) }
        return errs
    }

    public static func requireValid(_ ir: TimelineIR) throws {
        let errs = validate(ir)
        if !errs.isEmpty { throw IRInvalid(errors: errs) }
    }
}
