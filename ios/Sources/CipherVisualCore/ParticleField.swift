import Foundation

/// 内容无关的像素源：库只认像素。平台层负责把 UIImage / 文本渲染成它。
public protocol PixelSource: AnyObject {
    /// 内容是否仍可读（平台层：像素缓冲未释放）。不可读时引擎不建粒子场，按 contentUnavailable 拒绝 / 降为 teardown。
    /// 默认 true（见扩展）。2026-09-27 新增（contract-additive）。
    var isAvailable: Bool { get }
    var width: Int { get }
    var height: Int { get }
    /// 非预乘 ARGB（0xAARRGGBB）
    func argb(x: Int, y: Int) -> UInt32
}

public extension PixelSource {
    var isAvailable: Bool { true }
}

public final class ArrayPixels: PixelSource {
    public let width: Int
    public let height: Int
    private let pixels: [UInt32]

    public init(width: Int, height: Int, pixels: [UInt32]) {
        precondition(width > 0 && height > 0 && pixels.count == width * height, "像素尺寸不合法")
        self.width = width
        self.height = height
        self.pixels = pixels
    }

    public func argb(x: Int, y: Int) -> UInt32 { pixels[y * width + x] }
}

public struct Sample {
    public let x: Int
    public let y: Int
    public let color: UInt32
}

/// source 侧采样 + PRNG 续跑点（source 采样与 target 无关，支撑 prepare 预采样）。
public struct SampledSource {
    public let seed: UInt32
    public let gridPx: Int
    public let jitterPx: Int
    public let samples: [Sample]
    let rngState: UInt32
}

/// 粒子场 = f(seed, grid, jitter, source, target)，纯函数、确定性（spec/contracts.md §4）。SoA 存储。
public struct ParticleField {
    public let gridPx: Int
    public let sourceCount: Int
    public let targetCount: Int
    public let sx: [Double]
    public let sy: [Double]
    public let sc: [UInt32]
    public let tx: [Double]
    public let ty: [Double]
    public let tc: [UInt32]
    public let dx: [Double]
    public let dy: [Double]
    public let phase: [Double]

    public var count: Int { sx.count }

    public static func sampleSource(seed: UInt32, gridPx: Int, jitterPx: Int, source: PixelSource) -> SampledSource {
        var rng = Mulberry32(seed: seed)
        let samples = sample(source, grid: gridPx, jitter: jitterPx, rng: &rng)
        return SampledSource(seed: seed, gridPx: gridPx, jitterPx: jitterPx, samples: samples, rngState: rng.state)
    }

    public static func build(seed: UInt32, gridPx: Int, jitterPx: Int, source: PixelSource, target: PixelSource) -> ParticleField {
        build(sampleSource(seed: seed, gridPx: gridPx, jitterPx: jitterPx, source: source), target: target)
    }

    public static func build(_ source: SampledSource, target: PixelSource) -> ParticleField {
        var rng = Mulberry32(seed: source.rngState)
        let s = source.samples
        let t = sample(target, grid: source.gridPx, jitter: source.jitterPx, rng: &rng)
        let ns = s.count, nt = t.count, n = max(ns, nt)

        var perm = Array(0..<nt)
        if nt > 1 {
            for i in stride(from: nt - 1, through: 1, by: -1) {
                let j = Int(floor(rng.nextDouble() * Double(i + 1)))
                perm.swapAt(i, j)
            }
        }

        let zeros = [Double](repeating: 0, count: n)
        var sx = zeros, sy = zeros, tx = zeros, ty = zeros, dx = zeros, dy = zeros, phase = zeros
        var sc = [UInt32](repeating: 0, count: n)
        var tc = sc
        for i in 0..<n {
            let tgt: Sample? = nt > 0 ? t[perm[i % nt]] : nil
            let src: Sample? = ns > 0 ? s[i % ns] : nil
            let srcPos = src ?? tgt!
            let tgtPos = tgt ?? src!
            // 多出来的一侧借用循环样本/对侧位置，颜色置透明 → 淡入/淡出
            sx[i] = Double(srcPos.x)
            sy[i] = Double(srcPos.y)
            sc[i] = (src != nil && i < ns) ? src!.color : transparent(srcPos.color)
            tx[i] = Double(tgtPos.x)
            ty[i] = Double(tgtPos.y)
            tc[i] = (tgt != nil && i < nt) ? tgt!.color : transparent(tgtPos.color)
            let theta = rng.nextDouble() * 2 * Double.pi
            let k = 0.5 + rng.nextDouble()
            phase[i] = rng.nextDouble() * 2 * Double.pi
            dx[i] = cos(theta) * k
            dy[i] = sin(theta) * k
        }
        return ParticleField(gridPx: source.gridPx, sourceCount: ns, targetCount: nt,
                             sx: sx, sy: sy, sc: sc, tx: tx, ty: ty, tc: tc, dx: dx, dy: dy, phase: phase)
    }

    private static func transparent(_ c: UInt32) -> UInt32 { c & 0x00FF_FFFF }

    private static func sample(_ bitmap: PixelSource, grid: Int, jitter: Int, rng: inout Mulberry32) -> [Sample] {
        var out: [Sample] = []
        let cols = Int(ceil(Double(bitmap.width) / Double(grid)))
        let rows = Int(ceil(Double(bitmap.height) / Double(grid)))
        let half = grid / 2
        for gy in 0..<rows {
            for gx in 0..<cols {
                var jx = 0, jy = 0
                if jitter > 0 {
                    jx = Int(floor(rng.nextDouble() * Double(2 * jitter + 1))) - jitter
                    jy = Int(floor(rng.nextDouble() * Double(2 * jitter + 1))) - jitter
                }
                let x = min(max(gx * grid + half + jx, 0), bitmap.width - 1)
                let y = min(max(gy * grid + half + jy, 0), bitmap.height - 1)
                let c = bitmap.argb(x: x, y: y)
                if c >> 24 == 0 { continue }
                out.append(Sample(x: x, y: y, color: c))
            }
        }
        return out
    }
}

// MARK: - 轨迹

/// 一帧的粒子缓冲（SoA）。
public final class ParticleFrame {
    public private(set) var xs: [Float] = []
    public private(set) var ys: [Float] = []
    public private(set) var colors: [UInt32] = []
    public internal(set) var count = 0
    public internal(set) var particleSizePx: Float = 1

    func ensureCapacity(_ n: Int) {
        if xs.count < n {
            xs = [Float](repeating: 0, count: n)
            ys = [Float](repeating: 0, count: n)
            colors = [UInt32](repeating: 0, count: n)
        }
    }

    func set(_ i: Int, _ x: Double, _ y: Double, _ c: UInt32) {
        xs[i] = Float(x)
        ys[i] = Float(y)
        colors[i] = c
    }

    public func clear() {
        xs = []
        ys = []
        colors = []
        count = 0
    }
}

/// 粒子轨迹求值（spec/contracts.md §5），纯函数。公式见 Android `TrajectoryEvaluator`。
public struct TrajectoryEvaluator {
    public static let jitterAmplitudePx = 1.5
    public static let jitterPeriodMs = 1200.0

    let knobs: TimelineKnobs
    let driver: KeyframePrimitive
    let scatter: Double
    let sag: Double

    public init(_ ir: TimelineIR) {
        knobs = TimelineKnobs(ir)
        driver = KeyframePrimitive(spec: ir.primitives[ir.trajectory.driver]!)
        let phy = ir.trajectory.physics
        scatter = phy.v0 / phy.drag
        sag = phy.gravity / (phy.drag * phy.drag)
    }

    public func fill(_ field: ParticleField, _ t: Double, into out: ParticleFrame) {
        out.ensureCapacity(field.count)
        evaluate(field, t) { i, x, y, c in out.set(i, x, y, c) }
        out.count = field.count
        out.particleSizePx = Float(field.gridPx)
    }

    /// 测试 / golden 用：双精度输出。
    public func evaluate(_ field: ParticleField, _ t: Double) -> (x: [Double], y: [Double], c: [UInt32]) {
        var xs = [Double](repeating: 0, count: field.count)
        var ys = xs
        var cs = [UInt32](repeating: 0, count: field.count)
        evaluate(field, t) { i, x, y, c in
            xs[i] = x
            ys[i] = y
            cs[i] = c
        }
        return (xs, ys, cs)
    }

    private func evaluate(_ field: ParticleField, _ t: Double, _ sink: (Int, Double, Double, UInt32) -> Void) {
        let d = driver.next(t).value
        let m = knobs.morphAt(t)
        let jitterOn = knobs.holdMode == .jitter && t > knobs.morphEnd
        let w = (t - knobs.morphEnd) / Self.jitterPeriodMs * 2 * Double.pi
        for i in 0..<field.count {
            var x = field.sx[i] + (field.tx[i] - field.sx[i]) * m + d * field.dx[i] * scatter
            var y = field.sy[i] + (field.ty[i] - field.sy[i]) * m + d * (field.dy[i] * scatter + sag)
            if jitterOn {
                x += Self.jitterAmplitudePx * sin(w + field.phase[i])
                y += Self.jitterAmplitudePx * cos(w + field.phase[i])
            }
            sink(i, x, y, Self.lerpArgb(field.sc[i], field.tc[i], m))
        }
    }

    public static func lerpArgb(_ a: UInt32, _ b: UInt32, _ m: Double) -> UInt32 {
        var out: UInt32 = 0
        for shift in stride(from: 24, through: 0, by: -8) {
            let ca = Double((a >> UInt32(shift)) & 0xFF)
            let cb = Double((b >> UInt32(shift)) & 0xFF)
            out = (out << 8) | UInt32(floor(ca + (cb - ca) * m + 0.5))
        }
        return out
    }
}
