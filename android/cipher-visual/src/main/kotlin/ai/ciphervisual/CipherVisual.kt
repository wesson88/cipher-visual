package ai.ciphervisual

import ai.ciphervisual.core.Effect
import ai.ciphervisual.core.EngineConfig
import ai.ciphervisual.core.FrameClock
import ai.ciphervisual.core.HoldMode
import ai.ciphervisual.core.IrError
import ai.ciphervisual.core.IrInvalidException
import ai.ciphervisual.core.IrTemplates
import ai.ciphervisual.core.PlayRequest
import ai.ciphervisual.core.PlayResult
import ai.ciphervisual.core.PreparedSource
import ai.ciphervisual.core.TimelineIr
import ai.ciphervisual.core.VisualEngine
import ai.ciphervisual.core.VisualListener
import android.content.Context
import android.provider.Settings

/** 单次播放的可选项。 */
public data class PlayOptions(
    /** hold 相位模式开关——为什么开 jitter 归 App（灰区：能力 IN / 策略 OUT） */
    val holdMode: HoldMode = HoldMode.STATIC,
    /** 粒子场种子；双端同 seed + 同内容 → 同粒子场 */
    val seed: Long = IrTemplates.DEFAULT_SEED,
    val gridPx: Int = IrTemplates.DEFAULT_GRID_PX,
    /** 尊重系统「减少动效」：开启时降级为淡入淡出，锚点时序不变 */
    val respectReducedMotion: Boolean = true,
)

/**
 * `prepare(source)` 的结果：解析好的位图 + 预采样。**一次性**——播放结束时库会回收它持有的位图，重复 play 抛异常。
 */
public class PreparedContent internal constructor(
    internal val resolved: ResolvedContent,
    internal val sampled: PreparedSource,
    internal val options: PlayOptions,
) {
    internal var consumed: Boolean = false
}

/**
 * CipherVisual Android facade——对外能力的唯一入口。
 *
 * 库 = 视觉内容的时间轴变换引擎：输入 source / target / timeline，输出粒子渲染 + 锚点回调 + 句柄。
 * 它不知道内容是什么意思、为什么变、变完怎样，也从不调用任何其他库。主线程使用。
 */
public class CipherVisual(
    context: Context,
    config: EngineConfig = EngineConfig(),
    /** 默认 Choreographer；可注入同一个时钟给 CipherHaptic 贴帧 */
    public val frameClock: FrameClock = ChoreographerFrameClock(),
) {
    private val appContext = context.applicationContext
    public val engine: VisualEngine = VisualEngine(frameClock, config)

    /** 预热：提前解析并采样 source，play 时零采样延迟。何时预热归 App。 */
    public fun prepare(source: VisualContent, options: PlayOptions = PlayOptions()): PreparedContent {
        val resolved = ContentResolver.resolve(source)
        val sampled = engine.prepare(resolved.pixels, options.seed, options.gridPx, IrTemplates.DEFAULT_JITTER_PX)
        return PreparedContent(resolved, sampled, options)
    }

    public fun play(
        view: CipherVisualView,
        effect: Effect,
        source: VisualContent,
        target: VisualContent,
        holdMs: Long,
        options: PlayOptions = PlayOptions(),
        listener: VisualListener? = null,
    ): PlayResult = play(view, template(effect, holdMs, options), ContentResolver.resolve(source), null, target, options, listener)

    public fun play(
        view: CipherVisualView,
        effect: Effect,
        source: PreparedContent,
        target: VisualContent,
        holdMs: Long,
        listener: VisualListener? = null,
    ): PlayResult {
        check(!source.consumed) { "PreparedContent 是一次性的，已被使用" }
        source.consumed = true
        return play(view, template(effect, holdMs, source.options), source.resolved, source.sampled, target, source.options, listener)
    }

    /** 直接喂 IR（设计师工具导出 / 自定义时间轴）。IR 不合法抛 [IrInvalidException]。 */
    public fun play(
        view: CipherVisualView,
        ir: TimelineIr,
        source: VisualContent,
        target: VisualContent,
        options: PlayOptions = PlayOptions(),
        listener: VisualListener? = null,
    ): PlayResult = play(view, ir, ContentResolver.resolve(source), null, target, options, listener)

    /** 销毁：teardown 全部句柄并退订帧时钟。 */
    public fun release() {
        engine.release()
    }

    public fun isReducedMotionEnabled(): Boolean =
        Settings.Global.getFloat(appContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun play(
        view: CipherVisualView,
        ir: TimelineIr,
        source: ResolvedContent,
        prepared: PreparedSource?,
        target: VisualContent,
        options: PlayOptions,
        listener: VisualListener?,
    ): PlayResult {
        val tgt = ContentResolver.resolve(target)
        val reduced = options.respectReducedMotion && isReducedMotionEnabled()
        val result = try {
            engine.play(PlayRequest(ir, source.pixels, tgt.pixels, listener, reduced, prepared))
        } catch (e: IllegalArgumentException) {
            tgt.recycleIfOwned()
            throw e
        }
        when (result) {
            is PlayResult.Started -> view.attach(result.handle, source, tgt)
            is PlayResult.Queued -> view.attach(result.handle, source, tgt)
            PlayResult.Rejected -> tgt.recycleIfOwned()
        }
        return result
    }

    private fun template(effect: Effect, holdMs: Long, options: PlayOptions): TimelineIr = when (effect) {
        Effect.DISSOLVE -> IrTemplates.dissolve(holdMs.toDouble(), options.holdMode, options.seed, options.gridPx)
        else -> throw IrInvalidException(setOf(IrError.EFFECT_UNSUPPORTED))
    }
}
