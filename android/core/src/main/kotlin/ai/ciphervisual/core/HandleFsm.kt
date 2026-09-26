package ai.ciphervisual.core

/**
 * 句柄状态（5 态）。`active` 内部处于变换还是 hold 由 `phaseAt(t)` 派生，**不是子状态**。
 */
public enum class HandleState(public val wire: String) {
    IDLE("idle"),
    ACTIVE("active"),
    CANCELLING("cancelling"),
    COMPLETED("completed"),
    FAILED("failed");

    public val isTerminal: Boolean get() = this == COMPLETED || this == FAILED
}

/** 句柄事件（5 事件；cancel 按 mode 拆成两个 wire 名；withdraw = queue 策略下排队中撤回）。 */
public enum class HandleEvent(public val wire: String) {
    PLAY("play"),
    CANCEL_TEARDOWN("cancelTeardown"),
    CANCEL_REVERSE("cancelReverse"),
    REVERSE_DONE("reverseDone"),
    ERROR("error"),
    WITHDRAW("withdraw"),
}

/** 收回语义：`TEARDOWN` 瞬时清除（安全优先）| `REVERSE` 反向播放收回（体验优先）。选哪种归 App。 */
public enum class CancelMode { TEARDOWN, REVERSE }

/**
 * 转移表（spec/transitions.json）。纯函数；非法组合返回 null，由调用方拒绝并记日志。
 *
 * 纪律：能纯函数化的绝不进状态机——相位、hold 到期都不在这里。
 */
public object HandleFsm {
    public fun transition(state: HandleState, event: HandleEvent): HandleState? = when (state) {
        HandleState.IDLE -> when (event) {
            HandleEvent.PLAY -> HandleState.ACTIVE
            HandleEvent.WITHDRAW -> HandleState.COMPLETED
            else -> null
        }
        HandleState.ACTIVE -> when (event) {
            HandleEvent.CANCEL_TEARDOWN -> HandleState.COMPLETED
            HandleEvent.CANCEL_REVERSE -> HandleState.CANCELLING
            HandleEvent.ERROR -> HandleState.FAILED
            else -> null
        }
        HandleState.CANCELLING -> when (event) {
            HandleEvent.REVERSE_DONE -> HandleState.COMPLETED
            HandleEvent.CANCEL_TEARDOWN -> HandleState.COMPLETED
            HandleEvent.ERROR -> HandleState.FAILED
            else -> null
        }
        HandleState.COMPLETED, HandleState.FAILED -> null
    }
}
