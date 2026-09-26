package ai.ciphervisual

import ai.ciphervisual.core.FrameCallback
import ai.ciphervisual.core.FrameClock
import android.view.Choreographer

/**
 * Android 帧时钟：Choreographer vsync。必须在主线程（有 Looper 的线程）创建与使用。
 *
 * 同一个实例可交给 CipherHaptic 做贴帧——两库唯一的共享契约。
 */
public class ChoreographerFrameClock : FrameClock {
    private val choreographer = Choreographer.getInstance()
    private val wrappers = HashMap<FrameCallback, Choreographer.FrameCallback>()

    override fun postFrameCallback(callback: FrameCallback) {
        val w = wrappers.getOrPut(callback) {
            Choreographer.FrameCallback { nanos ->
                wrappers.remove(callback)
                callback.doFrame(nanos)
            }
        }
        choreographer.postFrameCallback(w)
    }

    override fun removeFrameCallback(callback: FrameCallback) {
        wrappers.remove(callback)?.let { choreographer.removeFrameCallback(it) }
    }
}
