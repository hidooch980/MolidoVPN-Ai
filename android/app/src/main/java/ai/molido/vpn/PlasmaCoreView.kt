package ai.molido.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import ai.molido.vpn.core.SessionState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Native Android hardware-accelerated Holographic Plasma Core View.
 * Renders an animated energy orb, rotating plasma rings, and glowing particles.
 */
class PlasmaCoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var rotationAngle = 0f
    private var pulseScale = 1f
    private var currentState: SessionState = SessionState.IDLE

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            rotationAngle = animation.animatedValue as Float
            val fraction = animation.animatedFraction
            pulseScale = 0.95f + 0.1f * sin(fraction * Math.PI.toFloat() * 2f)
            invalidate()
        }
    }

    private data class Particle(val angle: Float, val distRatio: Float, val radius: Float, val alpha: Int)
    private val particles = List(24) { i ->
        Particle(
            angle = (i * (360f / 24f)),
            distRatio = 0.55f + ((i % 5) * 0.08f),
            radius = 2.5f + (i % 3) * 1.5f,
            alpha = 100 + (i % 6) * 25
        )
    }

    init {
        animator.start()
    }

    fun setSessionState(state: SessionState) {
        if (currentState != state) {
            currentState = state
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = minOf(cx, cy) * 0.9f

        // Colors based on session state
        val (glowColor, coreCenterColor, coreEdgeColor) = when (currentState) {
            SessionState.CONNECTED -> Triple(
                Color.argb(80, 16, 185, 129),   // Emerald
                Color.rgb(52, 211, 153),
                Color.rgb(6, 95, 70)
            )
            SessionState.CONNECTING, SessionState.RECONNECTING -> Triple(
                Color.argb(90, 6, 182, 212),    // Cyan
                Color.rgb(56, 189, 248),
                Color.rgb(79, 70, 229)
            )
            SessionState.BLOCKED_ERROR, SessionState.OFFLINE_UNAVAILABLE -> Triple(
                Color.argb(80, 239, 68, 68),    // Red
                Color.rgb(248, 113, 113),
                Color.rgb(153, 27, 27)
            )
            else -> Triple(
                Color.argb(40, 100, 116, 139),  // Muted Slate
                Color.rgb(148, 163, 184),
                Color.rgb(30, 41, 59)
            )
        }

        // 1. Outer Halo
        haloPaint.shader = RadialGradient(
            cx, cy, maxRadius * pulseScale,
            intArrayOf(glowColor, Color.TRANSPARENT),
            floatArrayOf(0.3f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, maxRadius * pulseScale, haloPaint)

        // 2. Rotating Rings
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        ringPaint.color = Color.argb(120, Color.red(coreCenterColor), Color.green(coreCenterColor), Color.blue(coreCenterColor))
        canvas.drawCircle(cx, cy, maxRadius * 0.72f, ringPaint)

        // Segmented ring
        ringPaint.strokeWidth = 2f
        ringPaint.color = Color.argb(80, 255, 255, 255)
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45.0)).toFloat()
            val startX = cx + (maxRadius * 0.76f) * cos(a)
            val startY = cy + (maxRadius * 0.76f) * sin(a)
            val endX = cx + (maxRadius * 0.82f) * cos(a)
            val endY = cy + (maxRadius * 0.82f) * sin(a)
            canvas.drawLine(startX, startY, endX, endY, ringPaint)
        }
        canvas.restore()

        // 3. Floating Particles
        val particleSpeedMultiplier = if (currentState == SessionState.CONNECTED) 2f else 1f
        particles.forEach { p ->
            val rad = Math.toRadians((p.angle + rotationAngle * particleSpeedMultiplier).toDouble())
            val px = cx + (maxRadius * p.distRatio) * cos(rad).toFloat()
            val py = cy + (maxRadius * p.distRatio) * sin(rad).toFloat()
            particlePaint.color = Color.argb(p.alpha, Color.red(coreCenterColor), Color.green(coreCenterColor), Color.blue(coreCenterColor))
            canvas.drawCircle(px, py, p.radius, particlePaint)
        }

        // 4. Central Glowing Plasma Core
        val coreRadius = maxRadius * 0.48f * pulseScale
        corePaint.shader = RadialGradient(
            cx - coreRadius * 0.2f, cy - coreRadius * 0.2f, coreRadius,
            intArrayOf(coreCenterColor, coreEdgeColor),
            floatArrayOf(0.1f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        // 5. Inner Core Specular Highlight
        corePaint.shader = null
        corePaint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(cx - coreRadius * 0.25f, cy - coreRadius * 0.25f, coreRadius * 0.25f, corePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
