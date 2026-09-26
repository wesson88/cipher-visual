package ai.ciphervisual.core

/**
 * 硬件档——**复用 CipherHaptic 已探定的档位结论，库不重新探测**（边界 §八）。App 在初始化时传入。
 *
 * ⚠️ 预算默认值待真机标定（边界 §6.4）：现值是拍的，不是测的，P0 验证项「真机标定粒子预算」出数后替换。
 */
public enum class HardwareTier(public val defaultParticleBudget: Int) {
    /** 对应 CipherHaptic LINEAR_X_FULL 档。预算待真机标定。 */
    LINEAR_X_FULL(8000),

    /** 对应 CipherHaptic ERM_Z 档（低端）。预算待真机标定。 */
    ERM_Z(3000),
}

/**
 * 打满（超预算）时的策略——**选项归库、选择归外部、执行归库**（边界 §6.2）。不选时用 [DEGRADE] 兜底。
 */
public enum class OverflowStrategy(public val wire: String) {
    /** 新 handle 逐级加大 grid（粒子数约 1/4、1/16），保帧率、保全部显示 */
    DEGRADE("degrade"),

    /** 不降粒子，允许丢帧，保数量与单帧质量 */
    DROP_FRAME("dropFrame"),

    /** 新请求排队，等预算释放（FIFO，队头阻塞） */
    QUEUE("queue"),

    /** 拒绝新请求 */
    DROP_NEWEST("dropNewest"),

    /** teardown 最早启动、仍在耗预算的 handle，腾出空间 */
    DROP_OLDEST("dropOldest"),
}

/** 拒绝原因（契约，wire 名见 golden admission.json）。 */
public enum class RejectReason(public val wire: String) {
    /** `DROP_NEWEST` 下当前预算已占满 */
    BUDGET_FULL("budgetFull"),

    /** 单个请求在最低可用档即超总预算：排队会永久饿死队列、挤占也放不下，直接拒绝 */
    OVER_TOTAL_BUDGET("overTotalBudget"),

    /** 内容已不可读（如宿主提前回收了位图），无法建粒子场。引擎层判定，不经 [Admission] */
    CONTENT_UNAVAILABLE("contentUnavailable"),
}

public sealed interface AdmissionDecision {
    /** 以 grid 阶梯第 [gridLevel] 档启动；先 teardown [evict] 中的 handle */
    public data class Admit(val gridLevel: Int, val evict: List<Long>) : AdmissionDecision
    public data object Queue : AdmissionDecision
    public data class Reject(val reason: RejectReason) : AdmissionDecision
}

/** 一个正在耗预算的 handle（按启动先后排列）。 */
public data class RunningCost(val id: Long, val cost: Int)

/**
 * 预算裁决：纯函数（contracts/golden/admission.json）。
 *
 * @param ladderCosts 新 handle 在 grid、grid×2、grid×4 下的粒子数
 * @param queued 排在本请求前面的排队数。`QUEUE` 严格 FIFO：前面有人排队就排队尾，放得下也不插队
 */
public object Admission {
    public const val LADDER_STEPS: Int = 3

    public fun decide(
        strategy: OverflowStrategy,
        budget: Int,
        running: List<RunningCost>,
        ladderCosts: List<Int>,
        queued: Int = 0,
    ): AdmissionDecision {
        val usage = running.sumOf { it.cost }
        fun fits(c: Int, u: Int = usage) = u + c <= budget
        val bounded = strategy == OverflowStrategy.QUEUE || strategy == OverflowStrategy.DROP_NEWEST || strategy == OverflowStrategy.DROP_OLDEST
        if (bounded && ladderCosts[0] > budget) return AdmissionDecision.Reject(RejectReason.OVER_TOTAL_BUDGET)
        return when (strategy) {
            OverflowStrategy.DEGRADE -> {
                val level = ladderCosts.indexOfFirst { fits(it) }
                AdmissionDecision.Admit(if (level >= 0) level else ladderCosts.lastIndex, emptyList())
            }
            OverflowStrategy.DROP_FRAME -> AdmissionDecision.Admit(0, emptyList())
            OverflowStrategy.QUEUE ->
                if (queued == 0 && fits(ladderCosts[0])) AdmissionDecision.Admit(0, emptyList()) else AdmissionDecision.Queue
            OverflowStrategy.DROP_NEWEST ->
                if (fits(ladderCosts[0])) AdmissionDecision.Admit(0, emptyList()) else AdmissionDecision.Reject(RejectReason.BUDGET_FULL)
            OverflowStrategy.DROP_OLDEST -> {
                val evict = ArrayList<Long>()
                var u = usage
                for (r in running) {
                    if (fits(ladderCosts[0], u)) break
                    if (r.cost == 0) continue
                    evict += r.id
                    u -= r.cost
                }
                AdmissionDecision.Admit(0, evict)
            }
        }
    }
}
