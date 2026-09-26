package ai.ciphervisual.demo

import android.view.Choreographer

/**
 * 30 帧验证的观测器：只在有句柄耗粒子（变换 / jitter / reverse）时统计 vsync 间隔。
 *
 * 口径：avg fps = 帧数 / 窗口时长；p95 = 帧间隔 95 分位；jank = 间隔 > 33.3ms（跌破 30 帧）的帧数。
 * 这是软件侧观测，不替代 P0 真机标定（仪器测量 + 机型矩阵）。
 */
class FrameStats(private val isAnimating: () -> Boolean, private val onUpdate: (String) -> Unit) : Choreographer.FrameCallback {
    private val intervals = ArrayList<Double>()
    private var last = 0L
    private var running = false

    fun start() {
        if (running) return
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun reset() {
        intervals.clear()
        publish()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (isAnimating() && last != 0L) intervals += (frameTimeNanos - last) / 1_000_000.0
        last = frameTimeNanos
        if (intervals.size % 15 == 0) publish()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun publish() {
        if (intervals.isEmpty()) {
            onUpdate("帧统计：等待动画…")
            return
        }
        val sorted = intervals.sorted()
        val total = intervals.sum()
        val fps = intervals.size / (total / 1000.0)
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt()]
        val jank = intervals.count { it > 1000.0 / 30 }
        onUpdate("帧统计：%d 帧 · avg %.1f fps · p95 %.1f ms · 跌破30帧 %d 次".format(intervals.size, fps, p95, jank))
    }
}
