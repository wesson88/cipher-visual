package ai.ciphervisual.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 粒子轨迹求值（spec/contracts.md §5）：给定 IR + 粒子场 + t，算出每个粒子的位置与颜色。纯函数。
 *
 * ```
 * d = driver.next(t).value                     // 离散度（keyframe 0→1→0）
 * m = morphAt(t)                                // source→target 队形过渡，cubicInOut
 * x = lerp(sx, tx, m) + d · dx · (v0/drag)
 * y = lerp(sy, ty, m) + d · (dy · (v0/drag) + gravity/drag²)
 * c = lerpARGB(sc, tc, m)
 * ```
 * jitter hold（t > morphEnd）叠加 A·sin/cos(2π(t−morphEnd)/P + phase)。
 */
public class TrajectoryEvaluator(ir: TimelineIr) {
    private val knobs = TimelineKnobs(ir)
    private val driver: Primitive = KeyframePrimitive.from(ir.primitives.getValue(ir.trajectory.driver))
    private val scatter: Double
    private val sag: Double

    init {
        val phy = ir.trajectory.physics
        scatter = phy.v0 / phy.drag
        sag = phy.gravity / (phy.drag * phy.drag)
    }

    /** 渲染用：结果写入 [out]（复用缓冲，不分配）。 */
    public fun fill(field: ParticleField, t: Double, out: ParticleFrame) {
        out.ensureCapacity(field.size)
        evaluate(field, t) { i, x, y, c ->
            out.xs[i] = x.toFloat()
            out.ys[i] = y.toFloat()
            out.colors[i] = c
        }
        out.count = field.size
        out.particleSizePx = field.gridPx.toFloat()
    }

    /** 测试 / golden 用：双精度输出。 */
    public fun evaluate(field: ParticleField, t: Double): Triple<DoubleArray, DoubleArray, IntArray> {
        val xs = DoubleArray(field.size)
        val ys = DoubleArray(field.size)
        val cs = IntArray(field.size)
        evaluate(field, t) { i, x, y, c ->
            xs[i] = x
            ys[i] = y
            cs[i] = c
        }
        return Triple(xs, ys, cs)
    }

    private inline fun evaluate(field: ParticleField, t: Double, sink: (Int, Double, Double, Int) -> Unit) {
        val d = driver.next(t).value
        val m = knobs.morphAt(t)
        val jitterOn = knobs.holdMode == HoldMode.JITTER && t > knobs.morphEnd
        val w = (t - knobs.morphEnd) / JITTER_PERIOD_MS * 2 * PI
        for (i in 0 until field.size) {
            var x = field.sx[i] + (field.tx[i] - field.sx[i]) * m + d * field.dx[i] * scatter
            var y = field.sy[i] + (field.ty[i] - field.sy[i]) * m + d * (field.dy[i] * scatter + sag)
            if (jitterOn) {
                x += JITTER_AMPLITUDE_PX * sin(w + field.phase[i])
                y += JITTER_AMPLITUDE_PX * cos(w + field.phase[i])
            }
            sink(i, x, y, lerpArgb(field.sc[i], field.tc[i], m))
        }
    }

    public companion object {
        public const val JITTER_AMPLITUDE_PX: Double = 1.5
        public const val JITTER_PERIOD_MS: Double = 1200.0

        public fun lerpArgb(a: Int, b: Int, m: Double): Int {
            var out = 0
            var shift = 24
            while (shift >= 0) {
                val ca = (a ushr shift) and 0xFF
                val cb = (b ushr shift) and 0xFF
                out = (out shl 8) or floor(ca + (cb - ca) * m + 0.5).toInt()
                shift -= 8
            }
            return out
        }
    }
}

/** 一帧的粒子缓冲（SoA，float 精度够渲染）。 */
public class ParticleFrame {
    public var xs: FloatArray = FloatArray(0); private set
    public var ys: FloatArray = FloatArray(0); private set
    public var colors: IntArray = IntArray(0); private set
    public var count: Int = 0
    public var particleSizePx: Float = 1f

    public fun ensureCapacity(n: Int) {
        if (xs.size < n) {
            xs = FloatArray(n)
            ys = FloatArray(n)
            colors = IntArray(n)
        }
    }

    /** 释放缓冲（句柄终态时调用，库渲染完不留副本）。 */
    public fun clear() {
        xs = FloatArray(0)
        ys = FloatArray(0)
        colors = IntArray(0)
        count = 0
    }
}
