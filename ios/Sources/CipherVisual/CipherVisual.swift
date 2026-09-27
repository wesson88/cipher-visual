#if canImport(UIKit)
import CipherVisualCore
import UIKit

// MARK: - 帧时钟

/// iOS 帧时钟：CADisplayLink（主线程）。一次性回调语义同 Android Choreographer；无回调时暂停 display link。
public final class DisplayLinkFrameClock: FrameClock {
    private var link: CADisplayLink?
    private var callbacks: [FrameCallback] = []
    /// 延时回调：到期后转为普通帧回调（下一个 vsync 投递）；remove 时一并撤销
    private var delayed: [ObjectIdentifier: DispatchWorkItem] = [:]

    public init() {}

    public func postFrameCallback(_ callback: FrameCallback, delayMs: Double) {
        let key = ObjectIdentifier(callback)
        delayed[key]?.cancel()
        let item = DispatchWorkItem { [weak self] in
            self?.delayed[key] = nil
            self?.postFrameCallback(callback)
        }
        delayed[key] = item
        DispatchQueue.main.asyncAfter(deadline: .now() + max(delayMs, 0) / 1000, execute: item)
    }

    public func postFrameCallback(_ callback: FrameCallback) {
        callbacks.append(callback)
        if link == nil {
            let l = CADisplayLink(target: DisplayLinkProxy(self), selector: #selector(DisplayLinkProxy.tick(_:)))
            l.add(to: .main, forMode: .common)
            link = l
        }
        link?.isPaused = false
    }

    public func removeFrameCallback(_ callback: FrameCallback) {
        delayed.removeValue(forKey: ObjectIdentifier(callback))?.cancel()
        callbacks.removeAll { $0 === callback }
        if callbacks.isEmpty { link?.isPaused = true }
    }

    fileprivate func tick(_ link: CADisplayLink) {
        let nanos = UInt64(max(link.timestamp, 0) * 1_000_000_000)
        let cbs = callbacks
        callbacks.removeAll()
        cbs.forEach { $0.doFrame(nanos) }
        if callbacks.isEmpty { link.isPaused = true }
    }

    deinit {
        link?.invalidate()
    }
}

/// CADisplayLink 强引用 target：用弱代理断环。
private final class DisplayLinkProxy: NSObject {
    weak var clock: DisplayLinkFrameClock?

    init(_ clock: DisplayLinkFrameClock) {
        self.clock = clock
        super.init()
    }

    @objc func tick(_ link: CADisplayLink) {
        guard let clock = clock else {
            link.invalidate()
            return
        }
        clock.tick(link)
    }
}

// MARK: - 内容

/// 视觉内容抽象：纯文本 / 位图。业务富文本由 App 渲染成 `.image` 再喂——库不解析。
public enum VisualContent {
    case image(UIImage)
    case text(String, TextStyle)
}

public struct TextStyle {
    public var font: UIFont
    public var color: UIColor
    /// 换行宽度（pt）；nil = 单行按内容宽
    public var maxWidth: CGFloat?

    public init(font: UIFont, color: UIColor, maxWidth: CGFloat? = nil) {
        self.font = font
        self.color = color
        self.maxWidth = maxWidth
    }
}

/// 解析后的内容：CGImage + 非预乘 ARGB 像素源。像素缓冲在句柄终态时释放（库渲染完不留副本）。
public final class ResolvedContent: PixelSource {
    public let image: CGImage
    public let scale: CGFloat
    public let width: Int
    public let height: Int
    private var buffer: [UInt32]

    init?(_ image: CGImage, scale: CGFloat) {
        let w = image.width, h = image.height
        guard w > 0, h > 0 else { return nil }
        var buf = [UInt32](repeating: 0, count: w * h)
        let info = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        let ok: Bool = buf.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info) else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return nil }
        self.image = image
        self.scale = scale
        width = w
        height = h
        buffer = buf
    }

    /// 像素缓冲已释放（view.detach / 句柄终态）后不可读：引擎据此拒绝而不是建空粒子场
    public var isAvailable: Bool { !buffer.isEmpty }

    /// 与 Android `Bitmap.getPixel` 同口径：返回非预乘 ARGB。
    public func argb(x: Int, y: Int) -> UInt32 {
        guard !buffer.isEmpty else { return 0 }
        let p = buffer[y * width + x]
        let a = p >> 24
        guard a > 0, a < 255 else { return p }
        func un(_ c: UInt32) -> UInt32 { min(255, (c * 255 + a / 2) / a) }
        return (a << 24) | (un((p >> 16) & 0xFF) << 16) | (un((p >> 8) & 0xFF) << 8) | un(p & 0xFF)
    }

    func releasePixels() {
        buffer = []
    }

    /// 归一到目标 scale：粒子场把 source 坐标与 target 坐标混在同一空间插值，两者必须同一像素密度。
    /// 同 scale 时原样返回。
    func normalized(toScale target: CGFloat) -> ResolvedContent? {
        guard abs(scale - target) > 0.001 else { return self }
        let w = max(Int((CGFloat(width) / scale * target).rounded()), 1)
        let h = max(Int((CGFloat(height) / scale * target).rounded()), 1)
        let info = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let scaled = ctx.makeImage() else { return nil }
        return ResolvedContent(scaled, scale: target)
    }

    static func resolve(_ content: VisualContent) -> ResolvedContent? {
        switch content {
        case let .image(img):
            guard let cg = img.cgImage else { return nil }
            return ResolvedContent(cg, scale: img.scale)
        case let .text(str, style):
            let attrs: [NSAttributedString.Key: Any] = [.font: style.font, .foregroundColor: style.color]
            let s = NSAttributedString(string: str, attributes: attrs)
            let bound = CGSize(width: style.maxWidth ?? .greatestFiniteMagnitude, height: .greatestFiniteMagnitude)
            let rect = s.boundingRect(with: bound, options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
            let size = CGSize(width: max(ceil(rect.width), 1), height: max(ceil(rect.height), 1))
            let format = UIGraphicsImageRendererFormat.default()
            format.opaque = false
            let img = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                s.draw(with: CGRect(origin: .zero, size: size), options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
            }
            guard let cg = img.cgImage else { return nil }
            return ResolvedContent(cg, scale: img.scale)
        }
    }
}

// MARK: - 视图

/// 单个渲染位。坐标单位 = 位图像素，按 `scale` 映射到 pt。同一时刻只渲染一个句柄：
/// 新句柄挂上时若前一次正在 reverse 收回，继续画它直到终态再切到新句柄（与引擎取消交接一致）。
/// 粒子迸发会越出内容边界：宿主若不想被裁剪，令 `clipsToBounds = false`（默认即 false）。
public final class CipherVisualView: UIView {
    final class Contents {
        let source: ResolvedContent
        let target: ResolvedContent

        init(_ source: ResolvedContent, _ target: ResolvedContent) {
            self.source = source
            self.target = target
        }
    }

    /// 挂载簿记交给平台无关的 `RenderSlot`（与 Android 同一实现）
    private lazy var slot = RenderSlot<Contents>(
        onRelease: { c in
            c.source.releasePixels()
            c.target.releasePixels()
        },
        // 只在正在渲染的内容变了时重算尺寸；逐帧只重绘
        onLayoutChanged: { [weak self] in
            self?.invalidateIntrinsicContentSize()
            self?.setNeedsDisplay()
        },
        onRedraw: { [weak self] in self?.setNeedsDisplay() }
    )

    /// 最新挂上的句柄（可能仍在等交接，也可能已终态）
    public var attachedHandle: VisualHandle? { slot.latestHandle }
    /// 正在渲染的句柄（交接期间是还在倒放的前一次）
    public var renderingHandle: VisualHandle? { slot.currentHandle }

    public override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        isOpaque = false
        backgroundColor = .clear
    }

    func attach(_ handle: VisualHandle, source: ResolvedContent, target: ResolvedContent) {
        slot.attach(handle, Contents(source, target))
    }

    /// 解除挂载并释放库持有的像素。不改变句柄状态（收回请调 `handle.cancel`）。
    public func detach() {
        slot.detach()
    }

    public override var intrinsicContentSize: CGSize {
        // 正在渲染的优先：交接期间前一次还在倒放，尺寸不能提前跳到新内容
        guard let c = slot.layoutPayload else { return .zero }
        let s = c.target.scale
        return CGSize(width: CGFloat(max(c.source.width, c.target.width)) / s,
                      height: CGFloat(max(c.source.height, c.target.height)) / s)
    }

    public override func draw(_ rect: CGRect) {
        guard let h = slot.currentHandle, let b = slot.currentPayload, h.state != .idle,
              let ctx = UIGraphicsGetCurrentContext() else { return }
        // source 已在 play 时归一到 target 的 scale，二者同一像素空间
        let scale = b.target.scale
        ctx.saveGState()
        ctx.scaleBy(x: 1 / scale, y: 1 / scale)
        switch h.currentRender() {
        case .none:
            break
        case .staticTarget:
            drawImage(ctx, b.target, alpha: 1)
        case let .crossfade(alpha):
            drawImage(ctx, b.source, alpha: CGFloat(1 - alpha))
            drawImage(ctx, b.target, alpha: CGFloat(alpha))
        case let .particles(frame):
            drawParticles(ctx, frame)
        }
        ctx.restoreGState()
    }

    private func drawImage(_ ctx: CGContext, _ c: ResolvedContent, alpha: CGFloat) {
        // UIKit 上下文 y 轴向下；CGContext.draw 按 y 向上绘制，需翻转
        ctx.saveGState()
        ctx.setAlpha(alpha)
        ctx.translateBy(x: 0, y: CGFloat(c.height))
        ctx.scaleBy(x: 1, y: -1)
        ctx.draw(c.image, in: CGRect(x: 0, y: 0, width: c.width, height: c.height))
        ctx.restoreGState()
    }

    /// 相邻同色粒子合批成一次 fill。
    private func drawParticles(_ ctx: CGContext, _ f: ParticleFrame) {
        let n = f.count
        guard n > 0 else { return }
        let size = CGFloat(f.particleSizePx)
        let half = size / 2
        var rects: [CGRect] = []
        rects.reserveCapacity(n)
        var runColor = f.colors[0]
        func flush() {
            if runColor >> 24 != 0 && !rects.isEmpty {
                ctx.setFillColor(red: CGFloat((runColor >> 16) & 0xFF) / 255, green: CGFloat((runColor >> 8) & 0xFF) / 255,
                                 blue: CGFloat(runColor & 0xFF) / 255, alpha: CGFloat(runColor >> 24) / 255)
                ctx.fill(rects)
            }
            rects.removeAll(keepingCapacity: true)
        }
        for i in 0..<n {
            let c = f.colors[i]
            if c != runColor {
                flush()
                runColor = c
            }
            rects.append(CGRect(x: CGFloat(f.xs[i]) - half, y: CGFloat(f.ys[i]) - half, width: size, height: size))
        }
        flush()
    }
}

// MARK: - facade

public struct PlayOptions {
    public var holdMode: HoldMode
    public var seed: UInt32
    public var gridPx: Int
    /// 尊重系统「减少动效」：开启时降级为淡入淡出，锚点时序不变
    public var respectReducedMotion: Bool
    /// 同一 view 上「最新的取消前一次」时前一次的收回方式：默认 teardown；reverse 则倒放完、终态回调后新动效才启动
    public var preemptMode: CancelMode

    public init(holdMode: HoldMode = .static, seed: UInt32 = IRTemplates.defaultSeed,
                gridPx: Int = IRTemplates.defaultGridPx, respectReducedMotion: Bool = true,
                preemptMode: CancelMode = .teardown) {
        self.holdMode = holdMode
        self.seed = seed
        self.gridPx = gridPx
        self.respectReducedMotion = respectReducedMotion
        self.preemptMode = preemptMode
    }
}

public enum CipherVisualError: Error {
    case contentUnresolvable
}

/// CipherVisual iOS facade——对外能力的唯一入口（镜像 Android `CipherVisual`）。主线程使用。
public final class CipherVisual {
    public let engine: VisualEngine
    /// 可注入同一个时钟给 CipherHaptic 贴帧
    public let frameClock: FrameClock

    public init(config: EngineConfig = EngineConfig(), frameClock: FrameClock = DisplayLinkFrameClock()) {
        self.frameClock = frameClock
        engine = VisualEngine(clock: frameClock, config: config)
    }

    public var isReducedMotionEnabled: Bool { UIAccessibility.isReduceMotionEnabled }

    @discardableResult
    public func play(in view: CipherVisualView, effect: Effect, source: VisualContent, target: VisualContent,
                     holdMs: Int, options: PlayOptions = PlayOptions(), listener: VisualListener? = nil) throws -> PlayResult {
        guard effect == .dissolve else { throw IRInvalid(errors: [.EFFECT_UNSUPPORTED]) }
        let ir = IRTemplates.dissolve(holdMs: Double(holdMs), holdMode: options.holdMode, seed: options.seed, gridPx: options.gridPx)
        return try play(in: view, ir: ir, source: source, target: target, options: options, listener: listener)
    }

    /// 直接喂 IR。IR 不合法抛 `IRInvalid`。
    @discardableResult
    public func play(in view: CipherVisualView, ir: TimelineIR, source: VisualContent, target: VisualContent,
                     options: PlayOptions = PlayOptions(), listener: VisualListener? = nil) throws -> PlayResult {
        guard let tgt = ResolvedContent.resolve(target),
              let src = ResolvedContent.resolve(source)?.normalized(toScale: tgt.scale) else {
            throw CipherVisualError.contentUnresolvable
        }
        let reduced = options.respectReducedMotion && isReducedMotionEnabled
        // 渲染位 = view：同一 view 上新请求先取消前一次（库保证一个 view 同时只渲染一个句柄）
        let result = try engine.play(PlayRequest(ir: ir, source: src, target: tgt, listener: listener, reducedMotion: reduced,
                                                 slot: AnyHashable(ObjectIdentifier(view)), preemptMode: options.preemptMode))
        if let h = result.handle { view.attach(h, source: src, target: tgt) }
        return result
    }

    public func release() {
        engine.release()
    }
}
#endif
