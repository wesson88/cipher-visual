package ai.ciphervisual.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 对拍 contracts/golden 下的 json（由 tools/reference 的 JS 参考实现生成）。
 * iOS 端对拍同一批文件——两端各自对齐参考实现，双端一致性由此成立。
 */
class GoldenTest {
    private val dir = File(System.getProperty("cv.golden.dir") ?: "../../contracts/golden")
    private fun golden(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText()).jsonObject
    private val JsonElement.arr get() = jsonArray
    private val JsonElement.obj get() = jsonObject
    private val JsonElement.d get() = jsonPrimitive.double
    private val JsonElement.l get() = jsonPrimitive.long
    private val JsonElement.i get() = jsonPrimitive.int
    private val JsonElement.s get() = jsonPrimitive.content

    private fun near(expected: Double, actual: Double, what: String) {
        if (abs(expected - actual) > 1e-9) fail("$what: expected $expected, got $actual")
    }

    private fun pixels(o: JsonElement): PixelSource {
        val w = o.obj["width"]!!.i
        val h = o.obj["height"]!!.i
        return IntArrayPixels(w, h, o.obj["pixels"]!!.arr.map { it.l.toInt() }.toIntArray())
    }

    @Test
    fun prng() {
        for (c in golden("prng.json")["cases"]!!.arr) {
            val seed = c.obj["seed"]!!.l
            val r = Mulberry32(seed)
            assertEquals(c.obj["uint32"]!!.arr.map { it.l }, List(8) { r.nextUInt32() }, "seed=$seed")
            val r2 = Mulberry32(seed)
            c.obj["double"]!!.arr.forEach { near(it.d, r2.nextDouble(), "seed=$seed double") }
        }
    }

    @Test
    fun easingAndKeyframe() {
        val g = golden("primitive.json")
        for (e in g["easing"]!!.arr) {
            val ease = Ease.fromWire(e.obj["name"]!!.s)!!
            e.obj["p"]!!.arr.zip(e.obj["value"]!!.arr).forEach { (p, v) -> near(v.d, ease.apply(p.d), "${ease.wire}(${p.d})") }
        }
        for (k in g["keyframe"]!!.arr) {
            val spec = Json.decodeFromJsonElement(PrimitiveSpec.serializer(), k.obj["prim"]!!)
            val prim = KeyframePrimitive.from(spec)
            k.obj["t"]!!.arr.zip(k.obj["samples"]!!.arr).forEach { (t, s) ->
                val got = prim.next(t.d)
                near(s.obj["value"]!!.d, got.value, "keyframe t=${t.d}")
                assertEquals(s.obj["done"]!!.jsonPrimitive.boolean, got.done, "done t=${t.d}")
            }
        }
    }

    @Test
    fun timeline() {
        val g = golden("timeline.json")
        val phases = g["phases"]!!.arr.map { Json.decodeFromJsonElement(PhaseSpec.serializer(), it) }
        g["t"]!!.arr.zip(g["phase"]!!.arr).forEach { (t, p) ->
            assertEquals(if (p is JsonNull) null else p.s, phaseAt(phases, t.d), "phaseAt(${t.d})")
        }
    }

    @Test
    fun irTemplatesAndValidation() {
        val g = golden("ir.json")
        for (c in g["templates"]!!.arr) {
            val a = c.obj["args"]!!.obj
            val ir = IrTemplates.dissolve(
                holdMs = a["holdMs"]!!.d,
                holdMode = HoldMode.fromWire(a["holdMode"]!!.s)!!,
                seed = a["seed"]!!.l,
                gridPx = a["gridPx"]!!.i,
            )
            val expected = TimelineIr.parse(c.obj["ir"].toString())
            assertEquals(expected, ir)
            assertEquals(expected, TimelineIr.parse(ir.toJson()), "round-trip")
        }
        for (c in g["validation"]!!.arr) {
            val ir = TimelineIr.parse(c.obj["ir"].toString())
            val expected = c.obj["errors"]!!.arr.map { IrError.valueOf(it.s) }.toSet()
            assertEquals(expected, IrValidator.validate(ir), c.obj["name"]!!.s)
        }
    }

    @Test
    fun particleField() {
        for (c in golden("particle-field.json")["cases"]!!.arr) {
            val o = c.obj
            val name = o["name"]!!.s
            val f = ParticleField.build(o["seed"]!!.l, o["grid"]!!.i, o["jitter"]!!.i, pixels(o["source"]!!), pixels(o["target"]!!))
            val exp = o["field"]!!.obj
            assertEquals(exp["sourceCount"]!!.i, f.sourceCount, "$name sourceCount")
            assertEquals(exp["targetCount"]!!.i, f.targetCount, "$name targetCount")
            val ps = exp["particles"]!!.arr
            assertEquals(ps.size, f.size, "$name size")
            ps.forEachIndexed { i, p ->
                val q = p.obj
                near(q["sx"]!!.d, f.sx[i], "$name[$i].sx")
                near(q["sy"]!!.d, f.sy[i], "$name[$i].sy")
                near(q["tx"]!!.d, f.tx[i], "$name[$i].tx")
                near(q["ty"]!!.d, f.ty[i], "$name[$i].ty")
                assertEquals(q["sc"]!!.l.toInt(), f.sc[i], "$name[$i].sc")
                assertEquals(q["tc"]!!.l.toInt(), f.tc[i], "$name[$i].tc")
                near(q["dx"]!!.d, f.dx[i], "$name[$i].dx")
                near(q["dy"]!!.d, f.dy[i], "$name[$i].dy")
                near(q["phase"]!!.d, f.phase[i], "$name[$i].phase")
            }
        }
    }

    @Test
    fun preparedSourceMatchesOneShotBuild() {
        val c = golden("particle-field.json")["cases"]!!.arr[0].obj
        val src = pixels(c["source"]!!)
        val tgt = pixels(c["target"]!!)
        val seed = c["seed"]!!.l
        val oneShot = ParticleField.build(seed, 2, 1, src, tgt)
        val twoStep = ParticleField.build(ParticleField.sampleSource(seed, 2, 1, src), tgt)
        assertTrue(oneShot.tx.contentEquals(twoStep.tx) && oneShot.dx.contentEquals(twoStep.dx))
    }

    @Test
    fun trajectory() {
        for (c in golden("trajectory.json")["cases"]!!.arr) {
            val o = c.obj
            val ir = TimelineIr.parse(o["ir"].toString())
            val s = ir.model.sampler
            val field = ParticleField.build(ir.model.seed, s.gridPx, s.jitterPx, pixels(o["source"]!!), pixels(o["target"]!!))
            val ev = TrajectoryEvaluator(ir)
            val mode = o["holdMode"]!!.s
            o["t"]!!.arr.zip(o["frames"]!!.arr).forEach { (t, fr) ->
                val (xs, ys, cs) = ev.evaluate(field, t.d)
                val e = fr.obj
                e["x"]!!.arr.forEachIndexed { i, v -> near(v.d, xs[i], "$mode t=${t.d} x[$i]") }
                e["y"]!!.arr.forEachIndexed { i, v -> near(v.d, ys[i], "$mode t=${t.d} y[$i]") }
                assertEquals(e["c"]!!.arr.map { it.l.toInt() }, cs.toList(), "$mode t=${t.d} c")
            }
        }
    }

    @Test
    fun fsm() {
        val g = golden("fsm.json")
        assertEquals(g["states"]!!.arr.map { it.s }, HandleState.entries.map { it.wire })
        assertEquals(g["events"]!!.arr.map { it.s }, HandleEvent.entries.map { it.wire })
        for (row in g["table"]!!.arr) {
            val state = HandleState.entries.first { it.wire == row.obj["state"]!!.s }
            for ((ev, next) in row.obj["next"]!!.obj) {
                val event = HandleEvent.entries.first { it.wire == ev }
                val expected = if (next is JsonNull) null else HandleState.entries.first { it.wire == next.s }
                assertEquals(expected, HandleFsm.transition(state, event), "${state.wire} + $ev")
            }
        }
    }

    @Test
    fun admission() {
        for (c in golden("admission.json")["cases"]!!.arr) {
            val o = c.obj
            val strategy = OverflowStrategy.entries.first { it.wire == o["strategy"]!!.s }
            val running = o["running"]!!.arr.map { RunningCost(it.obj["id"]!!.l, it.obj["cost"]!!.i) }
            val ladder = o["ladder"]!!.arr.map { it.i }
            val d = o["decision"]!!.obj
            val expected = when (d["kind"]!!.s) {
                "admit" -> AdmissionDecision.Admit(d["grid"]!!.i, d["evict"]!!.arr.map { it.l })
                "queue" -> AdmissionDecision.Queue
                "reject" -> AdmissionDecision.Reject(RejectReason.entries.first { it.wire == d["reason"]!!.s })
                else -> error("unknown kind")
            }
            assertEquals(expected, Admission.decide(strategy, o["budget"]!!.i, running, ladder), "$strategy ${o["budget"]} $ladder")
        }
    }

    @Test
    fun effectsMatchSpec() {
        val spec = Json.parseToJsonElement(File(dir, "../../spec/effects.json").readText()).jsonObject
        val tokens = spec["effects"]!!.arr.map { it.obj }
        assertEquals(tokens.map { it["token"]!!.s }, Effect.entries.map { it.wire })
        tokens.forEach { t ->
            val e = Effect.fromWire(t["token"]!!.s)!!
            assertEquals(t["model"]!!.s, e.model.wire, e.wire)
            assertEquals(t["status"]!!.s == "mvp", e.implemented, e.wire)
        }
    }
}
