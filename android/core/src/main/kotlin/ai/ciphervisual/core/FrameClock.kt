package ai.ciphervisual.core

/**
 * 帧时钟契约——CipherVisual 与 CipherHaptic **唯一**的共享面（边界 §八）。
 *
 * 动效是 master：本库在帧回调里推进时间轴并触发锚点；触觉侧在锚点回调里 onNextFrame 贴帧。
 * 平台实现：Android = Choreographer，iOS = CADisplayLink；测试用手动时钟。
 */
public interface FrameClock {
    public fun postFrameCallback(callback: FrameCallback)
    public fun removeFrameCallback(callback: FrameCallback)
}

public fun interface FrameCallback {
    /** @param frameTimeNanos vsync 时间戳（单调时钟，ns） */
    public fun doFrame(frameTimeNanos: Long)
}
