package ai.ciphervisual.core

/**
 * 帧时钟契约——CipherVisual 与 CipherHaptic **唯一**的共享面（边界 §八）。
 *
 * 动效是 master：本库在帧回调里推进时间轴并触发锚点；触觉侧在锚点回调里 onNextFrame 贴帧。
 * 平台实现：Android = Choreographer，iOS = CADisplayLink；测试用手动时钟。
 */
public interface FrameClock {
    public fun postFrameCallback(callback: FrameCallback)

    /**
     * 至少 [delayMillis] 之后的下一个 vsync 回调（一次性）。引擎在「画面静止、只等下一个锚点」时用它，避免逐帧空转。
     *
     * 默认实现退化为下一帧（行为正确，只是逐帧唤醒）——实现方应覆盖。2026-09-27 新增（contract-additive）。
     */
    public fun postFrameCallbackDelayed(callback: FrameCallback, delayMillis: Long) {
        postFrameCallback(callback)
    }

    /** 同时撤销普通与延时的挂起回调。 */
    public fun removeFrameCallback(callback: FrameCallback)
}

public fun interface FrameCallback {
    /** @param frameTimeNanos vsync 时间戳（单调时钟，ns） */
    public fun doFrame(frameTimeNanos: Long)
}
