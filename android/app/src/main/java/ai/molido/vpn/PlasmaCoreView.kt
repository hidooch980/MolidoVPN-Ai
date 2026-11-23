package ai.molido.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import ai.molido.vpn.core.SessionState
import kotlin.math.sin

/**
 * "Liquid Glass" connection orb: a gently floating frosted-glass disc surrounded by a
 * rotating pink -> indigo -> cyan halo. Halo tint follows the VPN session state.
 * Public API (setSessionState) is unchanged from the previous plasma core.
 */
class PlasmaCoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var angle = 0f
    private var phase = 0f
    private var currentState: SessionState = SessionState.IDLE

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val f = it.animatedValue as Float
            angle = f * 360f
            phase = f
            invalidate()
        }
    }

    fun setSessionState(state: SessionState) {
        if (currentState != state) {
            currentState = state
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun palette(): IntArray = when (currentState) {
        SessionState.RUNNING -> intArrayOf(0xFF34D399.toInt(), 0xFF22D3EE.toInt(), 0xFF6366F1.toInt())
        SessionState.RESERVED -> intArrayOf(0xFF22D3EE.toInt(), 0xFF818CF8.toInt(), 0xFFF472B6.toInt())
        SessionState.STOPPING -> intArrayOf(0xFFFBBF24.toInt(), 0xFFF472B6.toInt(), 0xFFFB923C.toInt())
        SessionState.FAILED -> intArrayOf(0xFFF87171.toInt(), 0xFFF472B6.toInt(), 0xFFDC2626.toInt())
        SessionState.IDLE -> intArrayOf(0xFFF472B6.toInt(), 0xFF818CF8.toInt(), 0xFF22D3EE.toInt())
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = minOf(w, h) / 2f * 0.92f
        val cx = w / 2f
        val cy = h / 2f + sin(phase * Math.PI * 2).toFloat() * r * 0.04f
        val p = palette()

        // 1. Soft coloured glow
        glowPaint.shader = RadialGradient(cx, cy, r,
            intArrayOf(withAlpha(p[1], 110), withAlpha(p[0], 60), Color.TRANSPARENT),
            floatArrayOf(0.45f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, glowPaint)

        // 2. Rotating liquid gradient ring
        canvas.save()
        canvas.rotate(angle, cx, cy)
        ringPaint.strokeWidth = r * 0.16f
        ringPaint.shader = SweepGradient(cx, cy, intArrayOf(p[0], p[1], p[2], p[0]), null)
        ringPaint.alpha = 210
        canvas.drawCircle(cx, cy, r * 0.74f, ringPaint)
        canvas.restore()

        // 3. Frosted glass disc
        val gr = r * 0.62f
        glassPaint.shader = RadialGradient(cx - gr * 0.3f, cy - gr * 0.35f, gr * 1.4f,
            intArrayOf(Color.argb(250, 255, 255, 255), Color.argb(200, 238, 242, 255)),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, gr, glassPaint)
        edgePaint.strokeWidth = r * 0.02f
        canvas.drawCircle(cx, cy, gr, edgePaint)

        // 4. Specular highlight
        glassPaint.shader = null
        glassPaint.color = Color.argb(150, 255, 255, 255)
        canvas.drawCircle(cx - gr * 0.38f, cy - gr * 0.42f, gr * 0.18f, glassPaint)

        // 5. Power icon
        val ir = gr * 0.36f
        iconPaint.strokeWidth = r * 0.06f
        iconPaint.color = if (currentState == SessionState.RUNNING) 0xFF059669.toInt() else 0xFF6366F1.toInt()
        arcRect.set(cx - ir, cy - ir, cx + ir, cy + ir)
        canvas.drawArc(arcRect, -60f, 300f, false, iconPaint)
        canvas.drawLine(cx, cy - ir * 1.25f, cx, cy - ir * 0.15f, iconPaint)
    }
}
