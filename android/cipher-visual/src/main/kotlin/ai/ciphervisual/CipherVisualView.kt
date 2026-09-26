package ai.ciphervisual

import ai.ciphervisual.core.HandleState
import ai.ciphervisual.core.ParticleFrame
import ai.ciphervisual.core.RenderInstruction
import ai.ciphervisual.core.RenderSlot
import ai.ciphervisual.core.VisualHandle
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 单个渲染位（Canvas，MVP）。尺寸 = max(source, target) 内容尺寸。
 *
 * 挂载簿记交给平台无关的 [RenderSlot]：同一时刻只渲染一个句柄，前一次 reverse 收回期间继续画它，终态后切到新句柄；
 * 句柄终态即回收库持有的位图。
 * 粒子迸发会越出内容边界：宿主若不想被裁剪，需让父容器 `clipChildren = false`（布局归 App）。
 */
public class CipherVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    internal class Contents(val source: ResolvedContent, val target: ResolvedContent)

    private val slot = RenderSlot<Contents>(
        onRelease = {
            it.source.recycleIfOwned()
            it.target.recycleIfOwned()
        },
        onChanged = {
            requestLayout()
            invalidate()
        },
    )
    private var points = FloatArray(0)
    private val particlePaint = Paint().apply {
        isAntiAlias = false
        strokeCap = Paint.Cap.SQUARE
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 最新挂上的句柄（可能仍在等交接，也可能已终态）。 */
    public val attachedHandle: VisualHandle? get() = slot.latestHandle

    internal fun attach(handle: VisualHandle, source: ResolvedContent, target: ResolvedContent) {
        slot.attach(handle, Contents(source, target))
    }

    /** 解除挂载并释放库持有的位图。不改变句柄状态（收回请调 `handle.cancel`）。 */
    public fun detach() {
        slot.detach()
        points = FloatArray(0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = slot.latestPayload
        val w = max(c?.source?.width ?: 0, c?.target?.width ?: 0) + paddingLeft + paddingRight
        val h = max(c?.source?.height ?: 0, c?.target?.height ?: 0) + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val h = slot.currentHandle ?: return
        val c = slot.currentPayload ?: return
        if (h.state == HandleState.IDLE) return
        try {
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            when (val ins = h.currentRender()) {
                RenderInstruction.None -> Unit
                RenderInstruction.StaticTarget -> canvas.drawBitmap(c.target.bitmap, 0f, 0f, bitmapPaint)
                is RenderInstruction.Crossfade -> drawCrossfade(canvas, c, ins.targetAlpha)
                is RenderInstruction.Particles -> drawParticles(canvas, ins.frame)
            }
            canvas.restore()
        } catch (e: RuntimeException) {
            // 渲染失败要可见（→ failed），不静默降级
            h.reportError(e)
        }
    }

    private fun drawCrossfade(canvas: Canvas, c: Contents, targetAlpha: Float) {
        val a = (targetAlpha * 255).toInt().coerceIn(0, 255)
        bitmapPaint.alpha = 255 - a
        canvas.drawBitmap(c.source.bitmap, 0f, 0f, bitmapPaint)
        bitmapPaint.alpha = a
        canvas.drawBitmap(c.target.bitmap, 0f, 0f, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    /** 相邻同色粒子合批成一次 drawPoints（纯色文字 ≈ 1 次调用）。 */
    private fun drawParticles(canvas: Canvas, f: ParticleFrame) {
        val n = f.count
        if (n == 0) return
        if (points.size < n * 2) points = FloatArray(n * 2)
        particlePaint.strokeWidth = f.particleSizePx
        var runStart = 0
        var runColor = f.colors[0]
        for (i in 0..n) {
            val c = if (i < n) f.colors[i] else runColor.inv()
            if (i == n || c != runColor) {
                if (runColor ushr 24 != 0) {
                    var k = 0
                    for (j in runStart until i) {
                        points[k++] = f.xs[j]
                        points[k++] = f.ys[j]
                    }
                    particlePaint.color = runColor
                    canvas.drawPoints(points, 0, k, particlePaint)
                }
                runStart = i
                runColor = c
            }
        }
    }
}
