import Foundation
import XCTest
@testable import CipherVisualCore

/// 对拍 contracts/golden（JS 参考实现生成）——与 Android GoldenTest 读同一批文件。
final class GoldenTests: XCTestCase {
    private static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    private func golden(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: Self.root.appendingPathComponent("contracts/golden/\(name)"))
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    private func decode<T: Decodable>(_ type: T.Type, _ obj: Any) throws -> T {
        try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: obj))
    }

    private func num(_ v: Any?) -> NSNumber { v as! NSNumber }
    private func arr(_ v: Any?) -> [Any] { v as! [Any] }
    private func obj(_ v: Any?) -> [String: Any] { v as! [String: Any] }

    private func near(_ e: Double, _ a: Double, _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertLessThanOrEqual(abs(e - a), 1e-9, "\(what): expected \(e), got \(a)", file: file, line: line)
    }

    private func pixels(_ o: Any?) -> PixelSource {
        let m = obj(o)
        return ArrayPixels(width: num(m["width"]).intValue, height: num(m["height"]).intValue,
                           pixels: arr(m["pixels"]).map { num($0).uint32Value })
    }

    func testPrng() throws {
        for c in arr(try golden("prng.json")["cases"]) {
            let m = obj(c)
            let seed = num(m["seed"]).uint32Value
            var r = Mulberry32(seed: seed)
            XCTAssertEqual(arr(m["uint32"]).map { num($0).uint32Value }, (0..<8).map { _ in r.nextUInt32() }, "seed=\(seed)")
            var r2 = Mulberry32(seed: seed)
            for d in arr(m["double"]) { near(num(d).doubleValue, r2.nextDouble(), "seed=\(seed) double") }
        }
    }

    func testEasingAndKeyframe() throws {
        let g = try golden("primitive.json")
        for e in arr(g["easing"]) {
            let m = obj(e)
            let ease = Ease(rawValue: m["name"] as! String)!
            for (p, v) in zip(arr(m["p"]), arr(m["value"])) {
                near(num(v).doubleValue, ease.apply(num(p).doubleValue), "\(ease)")
            }
        }
        for k in arr(g["keyframe"]) {
            let m = obj(k)
            let prim = KeyframePrimitive(spec: try decode(PrimitiveSpec.self, m["prim"]!))
            for (t, s) in zip(arr(m["t"]), arr(m["samples"])) {
                let got = prim.next(num(t).doubleValue)
                near(num(obj(s)["value"]).doubleValue, got.value, "keyframe t=\(t)")
                XCTAssertEqual(num(obj(s)["done"]).boolValue, got.done, "done t=\(t)")
            }
        }
    }

    func testTimeline() throws {
        let g = try golden("timeline.json")
        let phases = try decode([PhaseSpec].self, g["phases"]!)
        for (t, p) in zip(arr(g["t"]), arr(g["phase"])) {
            XCTAssertEqual(p as? String, phaseAt(phases, num(t).doubleValue), "phaseAt(\(t))")
        }
    }

    func testIrTemplatesAndValidation() throws {
        let g = try golden("ir.json")
        for c in arr(g["templates"]) {
            let m = obj(c)
            let a = obj(m["args"])
            let ir = IRTemplates.dissolve(holdMs: num(a["holdMs"]).doubleValue, holdMode: HoldMode(rawValue: a["holdMode"] as! String)!,
                                          seed: num(a["seed"]).uint32Value, gridPx: num(a["gridPx"]).intValue)
            let expected = try decode(TimelineIR.self, m["ir"]!)
            XCTAssertEqual(expected, ir)
            XCTAssertEqual(expected, try TimelineIR.parse(ir.toJSON()), "round-trip")
        }
        for c in arr(g["validation"]) {
            let m = obj(c)
            let ir = try decode(TimelineIR.self, m["ir"]!)
            let expected = Set(arr(m["errors"]).map { IRError(rawValue: $0 as! String)! })
            XCTAssertEqual(expected, IRValidator.validate(ir), m["name"] as! String)
        }
    }

    func testParticleField() throws {
        for c in arr(try golden("particle-field.json")["cases"]) {
            let o = obj(c)
            let name = o["name"] as! String
            let f = ParticleField.build(seed: num(o["seed"]).uint32Value, gridPx: num(o["grid"]).intValue,
                                        jitterPx: num(o["jitter"]).intValue, source: pixels(o["source"]), target: pixels(o["target"]))
            let exp = obj(o["field"])
            XCTAssertEqual(num(exp["sourceCount"]).intValue, f.sourceCount, name)
            XCTAssertEqual(num(exp["targetCount"]).intValue, f.targetCount, name)
            let ps = arr(exp["particles"])
            XCTAssertEqual(ps.count, f.count, name)
            for (i, p) in ps.enumerated() {
                let q = obj(p)
                near(num(q["sx"]).doubleValue, f.sx[i], "\(name)[\(i)].sx")
                near(num(q["sy"]).doubleValue, f.sy[i], "\(name)[\(i)].sy")
                near(num(q["tx"]).doubleValue, f.tx[i], "\(name)[\(i)].tx")
                near(num(q["ty"]).doubleValue, f.ty[i], "\(name)[\(i)].ty")
                XCTAssertEqual(num(q["sc"]).uint32Value, f.sc[i], "\(name)[\(i)].sc")
                XCTAssertEqual(num(q["tc"]).uint32Value, f.tc[i], "\(name)[\(i)].tc")
                near(num(q["dx"]).doubleValue, f.dx[i], "\(name)[\(i)].dx")
                near(num(q["dy"]).doubleValue, f.dy[i], "\(name)[\(i)].dy")
                near(num(q["phase"]).doubleValue, f.phase[i], "\(name)[\(i)].phase")
            }
        }
    }

    func testTrajectory() throws {
        for c in arr(try golden("trajectory.json")["cases"]) {
            let o = obj(c)
            let ir = try decode(TimelineIR.self, o["ir"]!)
            let s = ir.model.sampler
            let field = ParticleField.build(seed: UInt32(ir.model.seed), gridPx: s.gridPx, jitterPx: s.jitterPx,
                                            source: pixels(o["source"]), target: pixels(o["target"]))
            let ev = TrajectoryEvaluator(ir)
            let mode = o["holdMode"] as! String
            for (t, fr) in zip(arr(o["t"]), arr(o["frames"])) {
                let got = ev.evaluate(field, num(t).doubleValue)
                let e = obj(fr)
                for (i, v) in arr(e["x"]).enumerated() { near(num(v).doubleValue, got.x[i], "\(mode) t=\(t) x[\(i)]") }
                for (i, v) in arr(e["y"]).enumerated() { near(num(v).doubleValue, got.y[i], "\(mode) t=\(t) y[\(i)]") }
                XCTAssertEqual(arr(e["c"]).map { num($0).uint32Value }, got.c, "\(mode) t=\(t) c")
            }
        }
    }

    func testFsm() throws {
        let g = try golden("fsm.json")
        XCTAssertEqual(arr(g["states"]) as! [String], HandleState.allCases.map { $0.rawValue })
        XCTAssertEqual(arr(g["events"]) as! [String], HandleEvent.allCases.map { $0.rawValue })
        for row in arr(g["table"]) {
            let r = obj(row)
            let state = HandleState(rawValue: r["state"] as! String)!
            for (ev, next) in obj(r["next"]) {
                let expected = (next as? String).flatMap { HandleState(rawValue: $0) }
                XCTAssertEqual(expected, HandleFSM.transition(state, HandleEvent(rawValue: ev)!), "\(state) + \(ev)")
            }
        }
    }

    func testAdmission() throws {
        for c in arr(try golden("admission.json")["cases"]) {
            let o = obj(c)
            let strategy = OverflowStrategy(rawValue: o["strategy"] as! String)!
            let running = arr(o["running"]).map { RunningCost(id: num(obj($0)["id"]).int64Value, cost: num(obj($0)["cost"]).intValue) }
            let ladder = arr(o["ladder"]).map { num($0).intValue }
            let d = obj(o["decision"])
            let expected: AdmissionDecision
            switch d["kind"] as! String {
            case "admit": expected = .admit(gridLevel: num(d["grid"]).intValue, evict: arr(d["evict"]).map { num($0).int64Value })
            case "queue": expected = .queue
            default: expected = .reject(RejectReason(rawValue: d["reason"] as! String)!)
            }
            XCTAssertEqual(expected, Admission.decide(strategy, budget: num(o["budget"]).intValue, running: running, ladderCosts: ladder,
                                                  queued: (o["queued"] as? NSNumber)?.intValue ?? 0),
                           "\(strategy) \(ladder)")
        }
    }

    func testEffectsMatchSpec() throws {
        let data = try Data(contentsOf: Self.root.appendingPathComponent("spec/effects.json"))
        let spec = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let tokens = arr(spec["effects"]).map { obj($0) }
        XCTAssertEqual(tokens.map { $0["token"] as! String }, Effect.allCases.map { $0.rawValue })
        for t in tokens {
            let e = Effect(rawValue: t["token"] as! String)!
            XCTAssertEqual(t["model"] as? String, e.model.rawValue, e.rawValue)
            XCTAssertEqual((t["status"] as? String) == "mvp", e.implemented, e.rawValue)
        }
    }
}
