package ai.ciphervisual

import ai.ciphervisual.core.PixelSource
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max

/**
 * 视觉内容抽象（边界尺子 2：内容无关）。
 *
 * 内置 resolver 只认两种：纯文本 / 位图。**业务富文本（高亮、@、表情排版）由 App 渲染成 [Image] 再喂**——库不解析。
 */
public sealed interface VisualContent {
    /** App 持有的位图；库只读取，不回收。 */
    public class Image(public val bitmap: Bitmap) : VisualContent

    /** 纯文本，库内部渲染成位图（库持有，句柄终态时回收）。 */
    public class Text(
        public val text: CharSequence,
        public val style: TextStyle,
    ) : VisualContent
}

public data class TextStyle(
    val textSizePx: Float,
    val color: Int,
    val typeface: Typeface? = null,
    /** 换行宽度；null = 单行按内容宽 */
    val maxWidthPx: Int? = null,
    val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
)

/** 已解析成位图的内容。 */
public class ResolvedContent internal constructor(
    public val bitmap: Bitmap,
    internal val owned: Boolean,
) {
    public val width: Int get() = bitmap.width
    public val height: Int get() = bitmap.height

    /**
     * 不拷贝整张像素：采样每格只读一个像素（`getPixel`），读的量是 w·h/grid²。
     * 只在粒子场构建时读取；构建完不再持有像素副本。
     */
    internal val pixels: PixelSource = object : PixelSource {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override fun argb(x: Int, y: Int): Int = bitmap.getPixel(x, y)

        /** 位图被回收（宿主 View.detach / App 自己 recycle）后不可读：引擎据此拒绝而不是去读像素 */
        override val isAvailable: Boolean get() = !bitmap.isRecycled
    }

    internal fun recycleIfOwned() {
        if (owned && !bitmap.isRecycled) bitmap.recycle()
    }
}

internal object ContentResolver {
    fun resolve(content: VisualContent): ResolvedContent = when (content) {
        is VisualContent.Image -> {
            val b = content.bitmap
            // HARDWARE 位图不可逐像素读：拷一份软件位图（库持有，终态回收）
            if (Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE) {
                ResolvedContent(b.copy(Bitmap.Config.ARGB_8888, false), owned = true)
            } else {
                ResolvedContent(b, owned = false)
            }
        }
        is VisualContent.Text -> ResolvedContent(renderText(content), owned = true)
    }

    private fun renderText(c: VisualContent.Text): Bitmap {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = c.style.textSizePx
            color = c.style.color
            c.style.typeface?.let { typeface = it }
        }
        val width = c.style.maxWidthPx
            ?: ceil(Layout.getDesiredWidth(c.text, paint).toDouble()).toInt()
        val layout = StaticLayout.Builder.obtain(c.text, 0, c.text.length, paint, max(width, 1))
            .setAlignment(c.style.alignment)
            .setIncludePad(false)
            .build()
        val bmp = Bitmap.createBitmap(max(width, 1), max(layout.height, 1), Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bmp))
        return bmp
    }
}
