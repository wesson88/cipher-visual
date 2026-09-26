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
 * 单个句柄的渲染面（Canvas，MVP）。尺寸 = max(source, target) 内容尺寸。
 *
 * 粒子迸发会越出内容边界：宿主若不想被裁剪，需让父容器 `clipChildren = false`（布局归 App）。
 * 句柄进入终态后自动清空画面并释放库持有的位图。
 */
public class CipherVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var handle: VisualHandle? = null
    private var source: ResolvedContent? = null
    private var target: ResolvedContent? = null
    private var points = FloatArray(0)
    private val particlePaint = Paint().apply {
        isAntiAlias = false
        strokeCap = Paint.Cap.SQUARE
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 当前挂载的句柄（可能已终态）。 */
    public val attachedHandle: VisualHandle? get() = handle

    internal fun attach(handle: VisualHandle, source: ResolvedContent, target: ResolvedContent) {
        detach()
        this.handle = handle
        this.source = source
        this.target = target
        handle.frameObserver = { onHandleFrame() }
        requestLayout()
        invalidate()
    }

    /** 解除挂载并释放库持有的位图。不改变句柄状态（收回请调 `handle.cancel`）。 */
    public fun detach() {
        handle?.frameObserver = null
        handle = null
        source?.recycleIfOwned()
        target?.recycleIfOwned()
        source = null
        target = null
        points = FloatArray(0)
        invalidate()
    }

    private fun onHandleFrame() {
        val h = handle ?: return
        if (h.state.isTerminal) {
            detach()
            return
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = max(source?.width ?: 0, target?.width ?: 0) + paddingLeft + paddingRight
        val h = max(source?.height ?: 0, target?.height ?: 0) + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDetachedFromWindow() {
        // 视图离屏只释放渲染资源；句柄生命周期归 App（库不替 App 决定收回）
        handle?.frameObserver = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handle?.frameObserver = { onHandleFrame() }
    }

    override fun onDraw(canvas: Canvas) {
        val h = handle ?: return
        if (h.state == HandleState.IDLE) return
        try {
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            when (val ins = h.currentRender()) {
                RenderInstruction.None -> Unit
                RenderInstruction.StaticTarget -> target?.let { canvas.drawBitmap(it.bitmap, 0f, 0f, bitmapPaint) }
                is RenderInstruction.Crossfade -> drawCrossfade(canvas, ins.targetAlpha)
                is RenderInstruction.Particles -> drawParticles(canvas, ins.frame)
            }
            canvas.restore()
        } catch (e: RuntimeException) {
            // 渲染失败要可见（→ failed），不静默降级
            h.reportError(e)
        }
    }

    private fun drawCrossfade(canvas: Canvas, targetAlpha: Float) {
        val a = (targetAlpha * 255).toInt().coerceIn(0, 255)
        source?.let {
            bitmapPaint.alpha = 255 - a
            canvas.drawBitmap(it.bitmap, 0f, 0f, bitmapPaint)
        }
        target?.let {
            bitmapPaint.alpha = a
            canvas.drawBitmap(it.bitmap, 0f, 0f, bitmapPaint)
        }
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
