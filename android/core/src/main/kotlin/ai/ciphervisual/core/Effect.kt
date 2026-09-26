package ai.ciphervisual.core

/** 动效模型（四层分层第二层）。 */
public enum class ModelType(public val wire: String) {
    PARTICLE_FIELD("particleField"),
    TRANSFORM("transform"),
    PATH("path"),
}

/**
 * effect 原语 token——视觉**动作类型**，不是业务场景（spec/effects.json，SSOT 为 vault「effect 原语集」）。
 *
 * 业务场景词一律拒收：场景由调用方用原语编排。`implemented = false` 的原语 IR 校验报 `EFFECT_UNSUPPORTED`。
 */
public enum class Effect(public val wire: String, public val model: ModelType, public val implemented: Boolean) {
    DISSOLVE("dissolve", ModelType.PARTICLE_FIELD, true),
    BURST("burst", ModelType.PARTICLE_FIELD, false),
    FALL("fall", ModelType.PARTICLE_FIELD, false),
    RISE("rise", ModelType.PARTICLE_FIELD, false),
    IMPACT("impact", ModelType.PARTICLE_FIELD, false),
    FADE("fade", ModelType.TRANSFORM, false),
    SLIDE("slide", ModelType.TRANSFORM, false),
    SHAKE("shake", ModelType.TRANSFORM, false),
    PULSE("pulse", ModelType.TRANSFORM, false),
    SCALE("scale", ModelType.TRANSFORM, false),
    SPIRAL("spiral", ModelType.PATH, false),
    ORBIT("orbit", ModelType.PATH, false);

    public companion object {
        public fun fromWire(wire: String): Effect? = entries.firstOrNull { it.wire == wire }
    }
}

/** hold 相位的渲染模式：`static` 画一次 target 静态挂（≈0 开销）| `jitter` 粒子持续游离（耗预算）。 */
public enum class HoldMode(public val wire: String) {
    STATIC("static"),
    JITTER("jitter");

    public companion object {
        public fun fromWire(wire: String?): HoldMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** 标准锚点 id。IR 锚点 id 是开放字符串，这里只列模板产出的几个。 */
public object Anchors {
    public const val ON_BURST: String = "onBurst"
    public const val ON_RESOLVE: String = "onResolve"
    public const val ON_HOLD_EXPIRED: String = "onHoldExpired"
}
