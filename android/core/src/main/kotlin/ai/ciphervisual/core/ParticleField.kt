package ai.ciphervisual.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * 内容无关的像素源（边界尺子 2）：库只认像素，不认文字/照片/业务富文本。
 * 平台层负责把 Bitmap / UIImage / 文本渲染成它。
 */
public interface PixelSource {
    public val width: Int
    public val height: Int

    /** 打包 ARGB（0xAARRGGBB） */
    public fun argb(x: Int, y: Int): Int

    /**
     * 内容是否仍可读（平台层：位图未被回收）。不可读时引擎不建粒子场，按 `contentUnavailable` 拒绝 / 降为 teardown。
     * 默认 true。2026-09-27 新增（contract-additive）。
     */
    public val isAvailable: Boolean get() = true
}

/** 行主序 IntArray 的 [PixelSource]。 */
public class IntArrayPixels(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : PixelSource {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "像素尺寸不合法" }
    }

    override fun argb(x: Int, y: Int): Int = pixels[y * width + x]
}

/** 单个网格采样点。 */
public class Sample(public val x: Int, public val y: Int, public val color: Int)

/**
 * source 侧的采样结果 + PRNG 续跑点。
 *
 * 采样对每个网格格子**无论是否透明**都固定消耗 PRNG（jitter > 0 时 2 次），所以 source 采样只依赖
 * source 尺寸与 grid/jitter，与 target 无关——这让 `prepare(source)` 预采样成立。
 */
public class SampledSource internal constructor(
    public val seed: Long,
    public val gridPx: Int,
    public val jitterPx: Int,
    public val samples: List<Sample>,
    internal val rngState: Long,
)

/**
 * 粒子场 = f(seed, grid, jitter, source, target)，纯函数、确定性（spec/contracts.md §4）。
 *
 * 以 SoA 存储，渲染每帧直接扫数组。
 */
public class ParticleField internal constructor(
    public val gridPx: Int,
    public val sourceCount: Int,
    public val targetCount: Int,
    public val sx: DoubleArray,
    public val sy: DoubleArray,
    public val sc: IntArray,
    public val tx: DoubleArray,
    public val ty: DoubleArray,
    public val tc: IntArray,
    /** 单位方向 × 速度系数 k ∈ [0.5, 1.5) */
    public val dx: DoubleArray,
    public val dy: DoubleArray,
    /** jitter hold 用的相位偏移 */
    public val phase: DoubleArray,
) {
    public val size: Int get() = sx.size

    public companion object {
        public fun sampleSource(seed: Long, gridPx: Int, jitterPx: Int, source: PixelSource): SampledSource {
            val rng = Mulberry32(seed)
            val samples = sample(source, gridPx, jitterPx, rng)
            return SampledSource(seed, gridPx, jitterPx, samples, rng.stateUInt32)
        }

        public fun build(seed: Long, gridPx: Int, jitterPx: Int, source: PixelSource, target: PixelSource): ParticleField =
            build(sampleSource(seed, gridPx, jitterPx, source), target)

        public fun build(source: SampledSource, target: PixelSource): ParticleField {
            val rng = Mulberry32.resume(source.rngState)
            val s = source.samples
            val t = sample(target, source.gridPx, source.jitterPx, rng)
            val ns = s.size
            val nt = t.size
            val n = max(ns, nt)

            val perm = IntArray(nt) { it }
            for (i in nt - 1 downTo 1) {
                val j = floor(rng.nextDouble() * (i + 1)).toInt()
                val tmp = perm[i]
                perm[i] = perm[j]
                perm[j] = tmp
            }

            val sx = DoubleArray(n)
            val sy = DoubleArray(n)
            val sc = IntArray(n)
            val tx = DoubleArray(n)
            val ty = DoubleArray(n)
            val tc = IntArray(n)
            val dx = DoubleArray(n)
            val dy = DoubleArray(n)
            val phase = DoubleArray(n)
            for (i in 0 until n) {
                val tgt = if (nt > 0) t[perm[i % nt]] else null
                val src = if (ns > 0) s[i % ns] else null
                // 多出来的一侧借用循环样本/对侧位置，颜色置透明 → 淡入/淡出
                sx[i] = (src ?: tgt!!).x.toDouble()
                sy[i] = (src ?: tgt!!).y.toDouble()
                sc[i] = if (src != null && i < ns) src.color else transparent((src ?: tgt!!).color)
                tx[i] = (tgt ?: src!!).x.toDouble()
                ty[i] = (tgt ?: src!!).y.toDouble()
                tc[i] = if (tgt != null && i < nt) tgt.color else transparent((tgt ?: src!!).color)
                val theta = rng.nextDouble() * 2 * PI
                val k = 0.5 + rng.nextDouble()
                phase[i] = rng.nextDouble() * 2 * PI
                dx[i] = cos(theta) * k
                dy[i] = sin(theta) * k
            }
            return ParticleField(source.gridPx, ns, nt, sx, sy, sc, tx, ty, tc, dx, dy, phase)
        }

        private fun transparent(c: Int): Int = c and 0x00FFFFFF

        private fun sample(bitmap: PixelSource, grid: Int, jitter: Int, rng: Mulberry32): List<Sample> {
            val out = ArrayList<Sample>()
            val cols = ceil(bitmap.width / grid.toDouble()).toInt()
            val rows = ceil(bitmap.height / grid.toDouble()).toInt()
            val half = grid / 2
            for (gy in 0 until rows) {
                for (gx in 0 until cols) {
                    var jx = 0
                    var jy = 0
                    if (jitter > 0) {
                        jx = floor(rng.nextDouble() * (2 * jitter + 1)).toInt() - jitter
                        jy = floor(rng.nextDouble() * (2 * jitter + 1)).toInt() - jitter
                    }
                    val x = (gx * grid + half + jx).coerceIn(0, bitmap.width - 1)
                    val y = (gy * grid + half + jy).coerceIn(0, bitmap.height - 1)
                    val c = bitmap.argb(x, y)
                    if (c ushr 24 == 0) continue
                    out += Sample(x, y, c)
                }
            }
            return out
        }
    }
}
