#if canImport(UIKit)
import CipherVisualCore
import UIKit
import XCTest
@testable import CipherVisual

/// #4：source 与 target 分辨率不同时，source 须归一到 target 的 scale（粒子场在同一像素空间插值）。
/// 仅在有 UIKit 的平台编译（CI 在 iOS 模拟器上跑）。
final class ScaleNormalizationTests: XCTestCase {
    private func image(pt: CGSize, scale: CGFloat) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: pt, format: format).image { ctx in
            UIColor.red.setFill()
            ctx.fill(CGRect(origin: .zero, size: pt))
        }
    }

    func testSourceIsRescaledToTargetScale() throws {
        let src = try XCTUnwrap(ResolvedContent.resolve(.image(image(pt: CGSize(width: 20, height: 10), scale: 1))))
        XCTAssertEqual(src.width, 20)
        let normalized = try XCTUnwrap(src.normalized(toScale: 3))
        XCTAssertEqual(normalized.scale, 3)
        XCTAssertEqual(normalized.width, 60, "同一 pt 尺寸在 3x 下是 60 像素")
        XCTAssertEqual(normalized.height, 30)
        XCTAssertEqual(normalized.argb(x: 30, y: 15) >> 24, 0xFF, "内容被保留")
    }

    func testSameScaleIsReturnedAsIs() throws {
        let src = try XCTUnwrap(ResolvedContent.resolve(.image(image(pt: CGSize(width: 20, height: 10), scale: 2))))
        XCTAssertTrue(src.normalized(toScale: 2) === src)
    }

    func testPlayNormalizesSourceToTargetScale() throws {
        let visual = CipherVisual(frameClock: DisplayLinkFrameClock())
        let view = CipherVisualView(frame: .zero)
        let font = UIFont.systemFont(ofSize: 17)
        let result = try visual.play(in: view, effect: .dissolve,
                                     source: .image(image(pt: CGSize(width: 40, height: 20), scale: 1)),
                                     target: .text("Hi", TextStyle(font: font, color: .black)), holdMs: 1000)
        XCTAssertNotNil(result.handle)
        // intrinsicContentSize 以 target scale 换算：1x 的 40pt 源在归一后仍是 40pt 宽，而不是被当成 40 像素
        XCTAssertGreaterThanOrEqual(view.intrinsicContentSize.width, 40 - 0.5)
        visual.release()
    }
}
#endif
