package ai.ciphervisual.demo

import ai.ciphervisual.CipherVisual
import ai.ciphervisual.CipherVisualView
import ai.ciphervisual.PlayOptions
import ai.ciphervisual.TextStyle
import ai.ciphervisual.VisualContent
import ai.ciphervisual.core.Anchors
import ai.ciphervisual.core.CancelMode
import ai.ciphervisual.core.Effect
import ai.ciphervisual.core.EngineConfig
import ai.ciphervisual.core.HandleState
import ai.ciphervisual.core.HardwareTier
import ai.ciphervisual.core.HoldMode
import ai.ciphervisual.core.OverflowStrategy
import ai.ciphervisual.core.PlayResult
import ai.ciphervisual.core.VisualHandle
import ai.ciphervisual.core.VisualListener
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * 独立验证 App（边界尺子 3：能脱离业务单独跑）。
 *
 * 演示的都是「App 侧职责」：何时播、hold 到期后收回方式（此处 reverse）、锚点上挂触觉——这些库都不替 App 做。
 */
class DemoActivity : Activity() {
    private val sources = listOf("Hello, particles", "The quick brown fox", "Lorem ipsum dolor", "Pack my box", "Sphinx of black quartz")
    private val targets = listOf("你好，粒子", "敏捷的棕色狐狸", "此处是占位文本", "装进我的盒子", "黑石英狮身像")

    private lateinit var visual: CipherVisual
    private lateinit var stats: FrameStats
    private lateinit var statusView: TextView
    private val rows = ArrayList<Pair<TextView, CipherVisualView>>()
    private var strategy = OverflowStrategy.DEGRADE
    private var tier = HardwareTier.LINEAR_X_FULL
    private var jitterHold = false
    private var holdMs = 3000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rebuildEngine()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            clipChildren = false
        }
        root.addView(TextView(this).apply {
            text = "CipherVisual · dissolve"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })
        statusView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        root.addView(statusView)

        root.addView(spinner(OverflowStrategy.entries.map { "打满策略: ${it.wire}" }) {
            strategy = OverflowStrategy.entries[it]
            rebuildEngine()
        })
        root.addView(spinner(HardwareTier.entries.map { "硬件档: ${it.name}（预算 ${it.defaultParticleBudget}，待标定）" }) {
            tier = HardwareTier.entries[it]
            rebuildEngine()
        })
        root.addView(spinner(listOf(3000L, 5000L, 30000L).map { "hold: ${it}ms" }) {
            holdMs = listOf(3000L, 5000L, 30000L)[it]
        })
        root.addView(CheckBox(this).apply {
            text = "hold.mode = jitter"
            setOnCheckedChangeListener { _, c -> jitterHold = c }
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("播 1 条") { playRow(0) })
        buttons.addView(button("并发 5 条") { rows.indices.forEach(::playRow) })
        root.addView(buttons)
        val buttons2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons2.addView(button("teardown 全部") { forEachHandle { it.cancel(CancelMode.TEARDOWN) } })
        buttons2.addView(button("reverse 全部") { forEachHandle { it.cancel(CancelMode.REVERSE) } })
        buttons2.addView(button("清统计") { stats.reset() })
        root.addView(buttons2)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, dp(24), 0, 0)
        }
        for (i in sources.indices) {
            val cell = FrameLayout(this).apply {
                clipChildren = false
                setPadding(0, dp(18), 0, dp(18))
            }
            val label = TextView(this).apply {
                text = sources[i]
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                setTextColor(Color.DKGRAY)
            }
            val cv = CipherVisualView(this)
            cell.addView(label)
            cell.addView(cv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(cell)
            rows += label to cv
        }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })

        stats = FrameStats({ visual.engine.particleUsage > 0 || visual.engine.activeHandles.any { it.state == HandleState.CANCELLING } }) {
            statusView.text = "$it\n粒子占用 ${visual.engine.particleUsage}/${visual.engine.config.particleBudget} · 减少动效=${visual.isReducedMotionEnabled()}"
        }
    }

    override fun onResume() {
        super.onResume()
        stats.start()
    }

    override fun onPause() {
        // App 策略示例：切后台立即 teardown（库不监听生命周期，这是 App 的决定）
        forEachHandle { it.cancel(CancelMode.TEARDOWN) }
        stats.stop()
        super.onPause()
    }

    override fun onDestroy() {
        visual.release()
        super.onDestroy()
    }

    private val textPx get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)

    private fun rebuildEngine() {
        if (::visual.isInitialized) visual.release()
        visual = CipherVisual(this, EngineConfig(hardwareTier = tier, overflowStrategy = strategy))
    }

    private fun playRow(i: Int) {
        val (label, cv) = rows[i]
        cv.attachedHandle?.takeIf { !it.state.isTerminal }?.cancel(CancelMode.TEARDOWN)
        val style = TextStyle(textPx, Color.DKGRAY)
        val listener = object : VisualListener {
            override fun onAnchor(handle: VisualHandle, anchorId: String) {
                when (anchorId) {
                    // App 编排示例：onBurst 挂触觉（库只发锚点，不持有触觉引擎）
                    Anchors.ON_BURST -> cv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    // hold 到期：App 选 reverse 优雅收回（库不自动收回）
                    Anchors.ON_HOLD_EXPIRED -> handle.cancel(CancelMode.REVERSE)
                }
            }

            override fun onStateChanged(handle: VisualHandle, state: HandleState) {
                // 动效期间由粒子层接管画面，结束后恢复原内容
                label.visibility = if (state == HandleState.ACTIVE || state == HandleState.CANCELLING) View.INVISIBLE else View.VISIBLE
            }
        }
        val result = visual.play(
            cv, Effect.DISSOLVE,
            VisualContent.Text(sources[i], style),
            VisualContent.Text(targets[i], style.copy(color = Color.rgb(0x30, 0x60, 0xE0))),
            holdMs,
            PlayOptions(holdMode = if (jitterHold) HoldMode.JITTER else HoldMode.STATIC),
            listener,
        )
        if (result == PlayResult.Rejected) statusView.append("\n第 ${i + 1} 条被拒（dropNewest）")
    }

    private fun forEachHandle(block: (VisualHandle) -> Unit) {
        rows.mapNotNull { it.second.attachedHandle }.filter { !it.state.isTerminal }.forEach(block)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun spinner(items: List<String>, onSelect: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@DemoActivity, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
