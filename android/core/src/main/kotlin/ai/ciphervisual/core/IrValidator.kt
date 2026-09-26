package ai.ciphervisual.core

/** IR 校验错误码。码集合是契约：双端对同一份 IR 必须产出同一集合（contracts/golden/ir.json）。 */
public enum class IrError {
    SCHEMA_VERSION,
    EFFECT_UNKNOWN,
    EFFECT_UNSUPPORTED,
    MODEL_TYPE,
    SAMPLER_GRID,
    SAMPLER_JITTER,
    SEED,
    PHASES_EMPTY,
    PHASE_ID_DUP,
    PHASE_RANGE,
    PHASE_START,
    PHASE_GAP,
    PHASE_MODE_SCOPE,
    PHASE_MODE,
    PHASE_SET,
    PHYSICS,
    DRIVER_REF,
    PRIM_TYPE,
    PRIM_SHAPE,
    PRIM_TIMES,
    PRIM_EASE,
    ANCHOR_ID_DUP,
    ANCHOR_RANGE,
}

public object IrValidator {
    private val DISSOLVE_PHASES = listOf("burst", "dissolve", "resolve", "hold")

    public fun validate(ir: TimelineIr): Set<IrError> {
        val errs = LinkedHashSet<IrError>()
        if (ir.schemaVersion != TimelineIr.SCHEMA_VERSION) errs += IrError.SCHEMA_VERSION
        val effect = Effect.fromWire(ir.effect)
        when {
            effect == null -> errs += IrError.EFFECT_UNKNOWN
            !effect.implemented -> errs += IrError.EFFECT_UNSUPPORTED
        }

        val m = ir.model
        if (m.type != ModelType.PARTICLE_FIELD.wire) errs += IrError.MODEL_TYPE
        val g = m.sampler.gridPx
        val j = m.sampler.jitterPx
        if (g < 1) errs += IrError.SAMPLER_GRID
        if (j < 0 || j >= g) errs += IrError.SAMPLER_JITTER
        if (m.seed < 0 || m.seed > 0xFFFFFFFFL) errs += IrError.SEED

        val phases = ir.phases
        if (phases.isEmpty()) errs += IrError.PHASES_EMPTY
        val ids = phases.map { it.id }
        if (ids.toSet().size != ids.size) errs += IrError.PHASE_ID_DUP
        phases.forEachIndexed { i, p ->
            if (p.range.size != 2 || !(p.start < p.end)) errs += IrError.PHASE_RANGE
            if (i == 0 && p.start != 0.0) errs += IrError.PHASE_START
            if (i > 0 && phases[i - 1].end != p.start) errs += IrError.PHASE_GAP
            if (p.mode != null) {
                if (p.id != "hold") errs += IrError.PHASE_MODE_SCOPE
                else if (HoldMode.fromWire(p.mode) == null) errs += IrError.PHASE_MODE
            }
        }
        if (effect == Effect.DISSOLVE && ids != DISSOLVE_PHASES) errs += IrError.PHASE_SET

        val phy = ir.trajectory.physics
        if (!(phy.v0 >= 0) || !(phy.drag > 0) || !phy.gravity.isFinite()) errs += IrError.PHYSICS
        if (ir.trajectory.driver !in ir.primitives) errs += IrError.DRIVER_REF
        for (p in ir.primitives.values) {
            if (p.type != "keyframe") {
                errs += IrError.PRIM_TYPE
                continue
            }
            if (p.times.size < 2 || p.times.size != p.values.size) {
                errs += IrError.PRIM_SHAPE
            } else if ((1 until p.times.size).any { !(p.times[it] > p.times[it - 1]) }) {
                errs += IrError.PRIM_TIMES
            }
            if (Ease.fromWire(p.ease) == null) errs += IrError.PRIM_EASE
        }

        val end = phases.lastOrNull()?.end ?: 0.0
        val aids = ir.anchors.map { it.id }
        if (aids.toSet().size != aids.size) errs += IrError.ANCHOR_ID_DUP
        if (ir.anchors.any { !(it.at >= 0 && it.at <= end) }) errs += IrError.ANCHOR_RANGE
        return errs
    }

    public fun requireValid(ir: TimelineIr) {
        val errs = validate(ir)
        if (errs.isNotEmpty()) throw IrInvalidException(errs)
    }
}
