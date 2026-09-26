import Foundation

/// 单个渲染位的挂载簿记（平台无关，平台 view 各持一个）：「正在渲染」+「等交接」两位。镜像 Android `RenderSlot`。
///
/// - 新句柄挂上时，若正在渲染的前一次还在收回（active / cancelling），新句柄进「等交接」位，前一次继续渲染到终态；
/// - 任何句柄到终态即释放其载荷，「等交接」随即顶上；
/// - 挂载时句柄已终态（如监听器在 active 回调里当场取消）也立即释放——终态通知已经错过，不能等下一次。
public final class RenderSlot<P> {
    private final class Bound {
        let handle: VisualHandle
        let payload: P

        init(_ handle: VisualHandle, _ payload: P) {
            self.handle = handle
            self.payload = payload
        }
    }

    private let onRelease: (P) -> Void
    private let onChanged: () -> Void
    private var current: Bound?
    private var pending: Bound?

    public init(onRelease: @escaping (P) -> Void, onChanged: @escaping () -> Void) {
        self.onRelease = onRelease
        self.onChanged = onChanged
    }

    /// 正在渲染的句柄与载荷（渲染器只画它）
    public var currentHandle: VisualHandle? { current?.handle }
    public var currentPayload: P? { current?.payload }
    /// 最新挂上的（可能在等交接）
    public var latestHandle: VisualHandle? { (pending ?? current)?.handle }
    public var latestPayload: P? { (pending ?? current)?.payload }

    public func attach(_ handle: VisualHandle, _ payload: P) {
        let b = Bound(handle, payload)
        if let cur = current, cur.handle.state != .idle, !cur.handle.state.isTerminal {
            if let p = pending { release(p) }
            pending = b
        } else {
            if let cur = current { release(cur) }
            current = b
        }
        handle.frameObserver = { [weak self] in
            self?.settle()
            self?.onChanged()
        }
        settle()
        onChanged()
    }

    /// 解除全部挂载并释放载荷。不改变句柄状态（收回请调 `handle.cancel`）。
    public func detach() {
        if let c = current { release(c) }
        if let p = pending { release(p) }
        current = nil
        pending = nil
        onChanged()
    }

    /// 终态的出位：正在渲染的终态 → 等交接的顶上（可能连续）；等交接的终态 → 直接释放。
    private func settle() {
        while let c = current, c.handle.state.isTerminal {
            release(c)
            current = pending
            pending = nil
        }
        if let p = pending, p.handle.state.isTerminal {
            release(p)
            pending = nil
        }
    }

    private func release(_ b: Bound) {
        b.handle.frameObserver = nil
        onRelease(b.payload)
    }
}
