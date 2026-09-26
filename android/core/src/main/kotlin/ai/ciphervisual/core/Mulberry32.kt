package ai.ciphervisual.core

/**
 * 粒子场种子协议的 PRNG（spec/contracts.md §3）。
 *
 * 双端必须逐位一致：同 seed → 同序列。任何地方改用平台随机数都会破坏「双端一致性是数学必然」这一前提，按 bug 处理。
 */
public class Mulberry32(seed: Long) {
    private var state: Int = seed.toInt()

    /** 当前内部状态（uint32），用于从中途续跑同一条序列。 */
    public val stateUInt32: Long get() = state.toLong() and MASK

    public fun nextUInt32(): Long {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = (t + (t xor (t ushr 7)) * (t or 61)) xor t
        return (t xor (t ushr 14)).toLong() and MASK
    }

    /** [0, 1) */
    public fun nextDouble(): Double = nextUInt32() / 4294967296.0

    public companion object {
        private const val MASK = 0xFFFFFFFFL

        /** 从某个已知状态续跑（[stateUInt32] 的逆）。 */
        public fun resume(state: Long): Mulberry32 = Mulberry32(state)
    }
}
