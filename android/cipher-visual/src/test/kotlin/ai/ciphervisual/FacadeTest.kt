package ai.ciphervisual

import ai.ciphervisual.core.CancelMode
import ai.ciphervisual.core.Effect
import ai.ciphervisual.core.EngineConfig
import ai.ciphervisual.core.FrameCallback
import ai.ciphervisual.core.FrameClock
import ai.ciphervisual.core.HandleState
import ai.ciphervisual.core.IrInvalidException
import ai.ciphervisual.core.OverflowStrategy
import ai.ciphervisual.core.PlayResult
import ai.ciphervisual.core.RejectReason
import android.content.Context
import android.graphics.Bitmap
import android.view.Choreographer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Android 框架层（facade / 视图 / Choreographer 时钟 / 内容可用性）——mockk 打桩 android.jar，JUnit5 运行。
 * 覆盖 code-review high 的 #4（facade 泄漏）、#5（交接期视图）、#2（位图回收后不可用）与选项 a（Choreographer 延时回调）。
 */
class FacadeTest {
    @AfterEach
    fun tearDown() = unmockkAll()

    /** 手动帧时钟（:core 的测试时钟不在本模块测试类路径上） */
    private class Clock : FrameClock {
        val callbacks = ArrayList<FrameCallback>()
        override fun postFrameCallback(callback: FrameCallback) {
            callbacks += callback
        }
        override fun removeFrameCallback(callback: FrameCallback) {
            callbacks -= callback
        }
        fun run(fromMs: Double, toMs: Double) {
            var t = fromMs
            while (t <= toMs) {
                val cbs = callbacks.toList()
                callbacks.clear()
                cbs.forEach { it.doFrame((t * 1_000_000).toLong()) }
                t += 1000.0 / 60
            }
        }
    }

    private fun bitmap(w: Int = 40, h: Int = 12): Bitmap = mockk(relaxed = true) {
        every { width } returns w
        every { height } returns h
        every { getPixel(any(), any()) } returns 0xFF112233.toInt()
        every { isRecycled } returns false
    }

    private fun owned(b: Bitmap = bitmap()) = ResolvedContent(b, owned = true)

    private val context: Context = mockk(relaxed = true)
    private val text = VisualContent.Text("x", TextStyle(10f, 0))
    private val noReduce = PlayOptions(respectReducedMotion = false)

    private fun visual(
        resolver: (VisualContent) -> ResolvedContent,
        config: EngineConfig = EngineConfig(),
        clock: FrameClock = Clock(),
    ) = CipherVisual(context, config, clock, resolver)

    // ---------------------------------------------------------------- #4 facade 泄漏

    @Test
    fun targetResolveFailureRecyclesAlreadyResolvedSource() {
        val src = bitmap()
        var calls = 0
        val cv = visual({ if (calls++ == 0) owned(src) else throw IllegalArgumentException("target too large") })
        assertFailsWith<IllegalArgumentException> {
            cv.play(CipherVisualView(context), Effect.DISSOLVE, text, text, 1000, noReduce)
        }
        verify(exactly = 1) { src.recycle() }
    }

    @Test
    fun unsupportedEffectResolvesNothing() {
        var calls = 0
        val cv = visual({ calls++; owned() })
        assertFailsWith<IrInvalidException> { cv.play(CipherVisualView(context), Effect.BURST, text, text, 1000, noReduce) }
        assertEquals(0, calls, "模板先于解析：不支持的 effect 抛出时没有任何库持有的位图")
    }

    @Test
    fun unsupportedEffectDoesNotConsumeOrLeakPreparedContent() {
        val src = bitmap()
        var first = true
        val cv = visual({ if (first) { first = false; owned(src) } else owned() })
        val prepared = cv.prepare(text, noReduce)
        assertFailsWith<IrInvalidException> { cv.play(CipherVisualView(context), Effect.BURST, prepared, text, 1000) }
        assertFalse(prepared.consumed, "抛出后 PreparedContent 仍可用")
        verify(exactly = 0) { src.recycle() }
        assertIs<PlayResult.Started>(cv.play(CipherVisualView(context), Effect.DISSOLVE, prepared, text, 1000))
    }

    @Test
    fun rejectedPlayRecyclesBothContents() {
        val src = bitmap()
        val tgt = bitmap()
        var calls = 0
        // 单请求 30 粒子 > 总预算 10 → overTotalBudget
        val cv = visual({ if (calls++ == 0) owned(src) else owned(tgt) }, EngineConfig(overflowStrategy = OverflowStrategy.QUEUE, particleBudget = 10))
        val r = cv.play(CipherVisualView(context), Effect.DISSOLVE, text, text, 1000, noReduce)
        assertEquals(PlayResult.Rejected(RejectReason.OVER_TOTAL_BUDGET), r)
        verify(exactly = 1) { src.recycle() }
        verify(exactly = 1) { tgt.recycle() }
    }

    // ---------------------------------------------------------------- #5 视图交接期：继续画前一次，终态后切换并回收

    @Test
    fun viewKeepsRenderingPreviousHandleDuringReverseHandoff() {
        val clock = Clock()
        val aSrc = bitmap()
        val bitmaps = ArrayDeque(listOf(aSrc, bitmap(), bitmap(), bitmap()))
        val cv = visual({ owned(bitmaps.removeFirst()) }, clock = clock)
        val view = CipherVisualView(context)
        val a = (cv.play(view, Effect.DISSOLVE, text, text, 1000, noReduce) as PlayResult.Started).handle
        clock.run(0.0, 300.0)
        val b = (cv.play(view, Effect.DISSOLVE, text, text, 1000, noReduce.copy(preemptMode = CancelMode.REVERSE)) as PlayResult.Queued).handle
        assertSame(a, view.renderingHandle, "前一次倒放期间视图继续画它")
        assertSame(b, view.attachedHandle)
        verify(exactly = 0) { aSrc.recycle() }
        clock.run(316.0, 1200.0)
        assertEquals(HandleState.COMPLETED, a.state)
        assertSame(b, view.renderingHandle, "前一次终态后切到新句柄")
        verify(exactly = 1) { aSrc.recycle() }
    }

    // ---------------------------------------------------------------- #2 位图回收后内容不可用

    @Test
    fun recycledBitmapMakesContentUnavailable() {
        val b = bitmap()
        val c = owned(b)
        assertTrue(c.pixels.isAvailable)
        every { b.isRecycled } returns true
        assertFalse(c.pixels.isAvailable, "引擎据此拒绝而不是去读已回收位图")
    }

    // ---------------------------------------------------------------- 选项 a：Choreographer 延时回调

    @Test
    fun choreographerClockUsesNativeDelayedCallbackAndRemovesIt() {
        val choreographer = mockk<Choreographer>(relaxed = true)
        mockkStatic(Choreographer::class)
        every { Choreographer.getInstance() } returns choreographer
        val clock = ChoreographerFrameClock()
        var fired = 0L
        val cb = FrameCallback { fired = it }
        val posted = slot<Choreographer.FrameCallback>()
        every { choreographer.postFrameCallbackDelayed(capture(posted), 4400) } returns Unit
        clock.postFrameCallbackDelayed(cb, 4400)
        verify(exactly = 1) { choreographer.postFrameCallbackDelayed(any(), 4400) }
        posted.captured.doFrame(123L)
        assertEquals(123L, fired, "到期后回调透传 vsync 时间戳")
        clock.postFrameCallbackDelayed(cb, 100)
        clock.removeFrameCallback(cb)
        verify { choreographer.removeFrameCallback(any()) }
    }
}
