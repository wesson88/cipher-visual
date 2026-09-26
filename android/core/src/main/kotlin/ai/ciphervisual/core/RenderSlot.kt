package ai.ciphervisual.core

/**
 * 单个渲染位的挂载簿记（平台无关，平台 View 各持一个）：「正在渲染」+「等交接」两位。
 *
 * - 新句柄挂上时，若正在渲染的前一次还在收回（active / cancelling），新句柄进「等交接」位，前一次继续渲染到终态；
 * - 任何句柄到终态即释放其载荷（平台层在 [onRelease] 里回收库持有的位图），「等交接」随即顶上；
 * - 挂载时句柄已终态（如监听器在 active 回调里当场取消）也立即释放——终态通知已经错过，不能等下一次。
 *
 * 与引擎的取消交接配合：引擎保证新句柄在前一次终态回调之后才启动，这里保证画面也在同一刻切换。
 */
public class RenderSlot<P : Any>(
    private val onRelease: (P) -> Unit,
    private val onChanged: () -> Unit,
) {
    private class Bound<P>(val handle: VisualHandle, val payload: P)

    private var current: Bound<P>? = null
    private var pending: Bound<P>? = null

    /** 正在渲染的句柄与载荷（渲染器只画它）。 */
    public val currentHandle: VisualHandle? get() = current?.handle
    public val currentPayload: P? get() = current?.payload

    /** 最新挂上的（可能在等交接）。 */
    public val latestHandle: VisualHandle? get() = (pending ?: current)?.handle
    public val latestPayload: P? get() = (pending ?: current)?.payload

    public fun attach(handle: VisualHandle, payload: P) {
        val b = Bound(handle, payload)
        val cur = current
        if (cur == null || cur.handle.state == HandleState.IDLE || cur.handle.state.isTerminal) {
            cur?.let(::release)
            current = b
        } else {
            pending?.let(::release)
            pending = b
        }
        handle.frameObserver = { onHandleFrame() }
        settle()
        onChanged()
    }

    /** 解除全部挂载并释放载荷。不改变句柄状态（收回请调 `handle.cancel`）。 */
    public fun detach() {
        current?.let(::release)
        pending?.let(::release)
        current = null
        pending = null
        onChanged()
    }

    private fun onHandleFrame() {
        settle()
        onChanged()
    }

    /** 终态的出位：正在渲染的终态 → 等交接的顶上（可能连续）；等交接的终态 → 直接释放。 */
    private fun settle() {
        while (true) {
            val c = current ?: break
            if (!c.handle.state.isTerminal) break
            release(c)
            current = pending
            pending = null
        }
        pending?.let {
            if (it.handle.state.isTerminal) {
                release(it)
                pending = null
            }
        }
    }

    private fun release(b: Bound<P>) {
        b.handle.frameObserver = null
        onRelease(b.payload)
    }
}
