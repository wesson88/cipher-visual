package ai.ciphervisual.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Timeline IR（schema_version 1）——字段与 vault「时间轴 IR SSOT」§八 一一对应，JSON 键名同 yaml。
 *
 * IR 只描述「怎么动」，不描述「动的是什么」；播放控制（cancel/reverse）不进 IR，归句柄状态机。
 */
@Serializable
public data class TimelineIr(
    @SerialName("schema_version") val schemaVersion: Int,
    val effect: String,
    val model: ModelSpec,
    val phases: List<PhaseSpec>,
    val trajectory: TrajectorySpec,
    val primitives: Map<String, PrimitiveSpec>,
    val anchors: List<AnchorSpec> = emptyList(),
) {
    public fun toJson(): String = JSON.encodeToString(serializer(), this)

    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        private val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
            encodeDefaults = true
        }

        /** 仅做结构解析；语义校验走 [IrValidator]。结构不合法抛 [IrParseException]。 */
        public fun parse(json: String): TimelineIr = try {
            JSON.decodeFromString(serializer(), json)
        } catch (e: SerializationException) {
            throw IrParseException(e.message ?: "IR JSON 结构不合法", e)
        } catch (e: IllegalArgumentException) {
            throw IrParseException(e.message ?: "IR JSON 结构不合法", e)
        }
    }
}

@Serializable
public data class ModelSpec(
    val type: String,
    val sampler: SamplerSpec,
    /** uint32 */
    val seed: Long,
)

@Serializable
public data class SamplerSpec(
    @SerialName("grid_px") val gridPx: Int,
    @SerialName("jitter_px") val jitterPx: Int,
)

@Serializable
public data class PhaseSpec(
    val id: String,
    /** [start, end]，ms */
    val range: List<Double>,
    val mode: String? = null,
) {
    val start: Double get() = range[0]
    val end: Double get() = range[1]
}

@Serializable
public data class TrajectorySpec(
    val physics: PhysicsSpec,
    val driver: String,
)

@Serializable
public data class PhysicsSpec(
    /** 初速度 px/s */
    val v0: Double,
    /** 阻力系数 */
    val drag: Double,
    /** 重力 px/s² */
    val gravity: Double,
)

@Serializable
public data class PrimitiveSpec(
    val type: String,
    val times: List<Double> = emptyList(),
    val values: List<Double> = emptyList(),
    val ease: String = Ease.LINEAR.wire,
)

@Serializable
public data class AnchorSpec(
    val id: String,
    /** ms */
    val at: Double,
)

public class IrParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

public class IrInvalidException(public val errors: Set<IrError>) :
    IllegalArgumentException("IR 校验失败: ${errors.joinToString()}")

// ------------------------------------------------------------------ 模板

/**
 * effect → 默认 Timeline 模板。App 只点名动作 + 给 source/target/holdMs，库内部产出完整 IR。
 *
 * `holdMs` 是 hold 相位**时长**：hold = [600, 600 + holdMs]，`onHoldExpired` 落在 hold 末端。
 */
public object IrTemplates {
    public const val DEFAULT_SEED: Long = 0x3f2a1c7bL
    public const val DEFAULT_GRID_PX: Int = 4
    public const val DEFAULT_JITTER_PX: Int = 1

    public fun dissolve(
        holdMs: Double,
        holdMode: HoldMode = HoldMode.STATIC,
        seed: Long = DEFAULT_SEED,
        gridPx: Int = DEFAULT_GRID_PX,
        jitterPx: Int = DEFAULT_JITTER_PX,
    ): TimelineIr {
        require(holdMs > 0) { "holdMs 必须 > 0" }
        val holdEnd = 600 + holdMs
        return TimelineIr(
            schemaVersion = TimelineIr.SCHEMA_VERSION,
            effect = Effect.DISSOLVE.wire,
            model = ModelSpec(ModelType.PARTICLE_FIELD.wire, SamplerSpec(gridPx, jitterPx), seed and 0xFFFFFFFFL),
            phases = listOf(
                PhaseSpec("burst", listOf(0.0, 150.0)),
                PhaseSpec("dissolve", listOf(150.0, 350.0)),
                PhaseSpec("resolve", listOf(350.0, 600.0)),
                PhaseSpec("hold", listOf(600.0, holdEnd), holdMode.wire),
            ),
            trajectory = TrajectorySpec(PhysicsSpec(v0 = 300.0, drag = 4.0, gravity = 200.0), driver = "prim_burst"),
            primitives = mapOf(
                "prim_burst" to PrimitiveSpec("keyframe", listOf(0.0, 150.0, 600.0), listOf(0.0, 1.0, 0.0), Ease.CUBIC_OUT.wire),
            ),
            anchors = listOf(
                AnchorSpec(Anchors.ON_BURST, 0.0),
                AnchorSpec(Anchors.ON_RESOLVE, 600.0),
                AnchorSpec(Anchors.ON_HOLD_EXPIRED, holdEnd),
            ),
        )
    }
}

// ------------------------------------------------------------------ 相位

/** 相位是纯函数不是状态：区间左闭右开，最后一个相位右端闭合；区间外返回 null。 */
public fun phaseAt(phases: List<PhaseSpec>, t: Double): String? {
    for ((i, p) in phases.withIndex()) {
        val isLast = i == phases.lastIndex
        if (t >= p.start && (t < p.end || (isLast && t == p.end))) return p.id
    }
    return null
}

/**
 * 从 IR 派生、播放器要用的几个时间量。只对已通过 [IrValidator] 的 dissolve IR 成立。
 */
public class TimelineKnobs(ir: TimelineIr) {
    /** morph 窗口 = [burst.end, hold.start]：粒子从 source 队形过渡到 target 队形 */
    public val morphStart: Double = ir.phases.first { it.id == "burst" }.end
    public val morphEnd: Double = ir.phases.first { it.id == "hold" }.start
    public val holdEnd: Double = ir.phases.first { it.id == "hold" }.end
    public val holdMode: HoldMode = HoldMode.fromWire(ir.phases.first { it.id == "hold" }.mode) ?: HoldMode.STATIC

    public fun morphAt(t: Double): Double = when {
        t <= morphStart -> 0.0
        t >= morphEnd -> 1.0
        else -> Ease.CUBIC_IN_OUT.apply((t - morphStart) / (morphEnd - morphStart))
    }
}
