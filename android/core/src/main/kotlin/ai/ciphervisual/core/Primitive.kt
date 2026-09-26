package ai.ciphervisual.core

/** 缓动曲线。wire 名即 IR 中 `ease` 字段取值。 */
public enum class Ease(public val wire: String) {
    LINEAR("linear"),
    CUBIC_IN("cubicIn"),
    CUBIC_OUT("cubicOut"),
    CUBIC_IN_OUT("cubicInOut");

    public fun apply(p: Double): Double = when (this) {
        LINEAR -> p
        CUBIC_IN -> p * p * p
        CUBIC_OUT -> {
            val q = 1 - p
            1 - q * q * q
        }
        CUBIC_IN_OUT -> if (p < 0.5) {
            4 * p * p * p
        } else {
            val q = -2 * p + 2
            1 - q * q * q / 2
        }
    }

    public companion object {
        public fun fromWire(wire: String): Ease? = entries.firstOrNull { it.wire == wire }
    }
}

/** 原语采样结果：`next(t) → { value, done }`。 */
public data class PrimitiveSample(val value: Double, val done: Boolean)

/**
 * 原语契约：时间→值的纯函数，无状态、不知道帧时钟、任意 t 可采样（IR SSOT §六）。
 * MVP 只有 keyframe；spring / tween / curve 留 V2。
 */
public fun interface Primitive {
    public fun next(t: Double): PrimitiveSample
}

public class KeyframePrimitive(
    private val times: DoubleArray,
    private val values: DoubleArray,
    private val ease: Ease,
) : Primitive {
    init {
        require(times.size >= 2 && times.size == values.size) { "keyframe times/values 长度不合法" }
    }

    override fun next(t: Double): PrimitiveSample {
        val last = times.size - 1
        if (t <= times[0]) return PrimitiveSample(values[0], false)
        if (t >= times[last]) return PrimitiveSample(values[last], true)
        var i = 0
        while (t >= times[i + 1]) i++
        val p = (t - times[i]) / (times[i + 1] - times[i])
        return PrimitiveSample(values[i] + (values[i + 1] - values[i]) * ease.apply(p), false)
    }

    public companion object {
        public fun from(spec: PrimitiveSpec): KeyframePrimitive = KeyframePrimitive(
            spec.times.toDoubleArray(),
            spec.values.toDoubleArray(),
            requireNotNull(Ease.fromWire(spec.ease)) { "unknown ease: ${spec.ease}" },
        )
    }
}
