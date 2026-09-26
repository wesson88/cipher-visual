package ai.ciphervisual

import ai.ciphervisual.core.HandleState
import ai.ciphervisual.core.ParticleFrame
import ai.ciphervisual.core.RenderInstruction
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
 * 同一时刻只渲染一个句柄。新句柄挂上来时，若前一次正在 reverse 收回，继续画它直到终态，再切到新句柄
 * （与引擎的取消交接一致：前一次终态回调之后新句柄才启动）。
 * 粒子迸发会越出内容边界：宿主若不想被裁剪，需让父容器 `clipChildren = false`（布局归 App）。
 */
public class CipherVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private class Bound(val handle: VisualHandle, val source: ResolvedContent, val target: ResolvedContent)

    /** 正在渲染的 */
    private var current: Bound? = null

    /** 等交接的（前一次收回完成后切上来） */
    private var pending: Bound? = null
    private var points = FloatArray(0)
    private val particlePaint = Paint().apply {
        isAntiAlias = false
        strokeCap = Paint.Cap.SQUARE
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 最新挂上的句柄（可能仍在等交接，也可能已终态）。 */
    public val attachedHandle: VisualHandle? get() = (pending ?: current)?.handle

    internal fun attach(handle: VisualHandle, source: ResolvedContent, target: ResolvedContent) {
        val b = Bound(handle, source, target)
        val cur = current
        if (cur == null || cur.handle.state.isTerminal || cur.handle.state == HandleState.IDLE) {
            cur?.let(::release)
            current = b
        } else {
            // 前一次还在收回（reverse）：继续画它，新句柄排在后面
            pending?.let(::release)
            pending = b
        }
        handle.frameObserver = { onHandleFrame(handle) }
        requestLayout()
        invalidate()
    }

    /** 解除挂载并释放库持有的位图。不改变句柄状态（收回请调 `handle.cancel`）。 */
    public fun detach() {
        current?.let(::release)
        pending?.let(::release)
        current = null
        pending = null
        points = FloatArray(0)
        invalidate()
    }

    private fun release(b: Bound) {
        b.handle.frameObserver = null
        b.source.recycleIfOwned()
        b.target.recycleIfOwned()
    }

    private fun onHandleFrame(h: VisualHandle) {
        if (h.state.isTerminal) {
            val cur = current
            val pen = pending
            when {
                cur?.handle === h -> {
                    release(cur)
                    current = pen
                    pending = null
                    if (pen != null) requestLayout()
                }
                pen?.handle === h -> {
                    release(pen)
                    pending = null
                }
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val b = pending ?: current
        val w = max(b?.source?.width ?: 0, b?.target?.width ?: 0) + paddingLeft + paddingRight
        val h = max(b?.source?.height ?: 0, b?.target?.height ?: 0) + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val b = current ?: return
        val h = b.handle
        if (h.state == HandleState.IDLE) return
        try {
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            when (val ins = h.currentRender()) {
                RenderInstruction.None -> Unit
                RenderInstruction.StaticTarget -> canvas.drawBitmap(b.target.bitmap, 0f, 0f, bitmapPaint)
                is RenderInstruction.Crossfade -> drawCrossfade(canvas, b, ins.targetAlpha)
                is RenderInstruction.Particles -> drawParticles(canvas, ins.frame)
            }
            canvas.restore()
        } catch (e: RuntimeException) {
            // 渲染失败要可见（→ failed），不静默降级
            h.reportError(e)
        }
    }

    private fun drawCrossfade(canvas: Canvas, b: Bound, targetAlpha: Float) {
        val a = (targetAlpha * 255).toInt().coerceIn(0, 255)
        bitmapPaint.alpha = 255 - a
        canvas.drawBitmap(b.source.bitmap, 0f, 0f, bitmapPaint)
        bitmapPaint.alpha = a
        canvas.drawBitmap(b.target.bitmap, 0f, 0f, bitmapPaint)
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
