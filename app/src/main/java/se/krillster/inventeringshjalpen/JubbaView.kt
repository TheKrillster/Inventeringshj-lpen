package se.krillster.inventeringshjalpen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Påskägg: en lagergubbe med hjälm som promenerar in, vinkar och säger "Jubba!" i en pratbubbla.
 * Ligger ovanpå skärmen men tar inte emot tryck.
 */
class JubbaView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private var t = -1f                      // ms sedan start, -1 = inte igång
    private var anim: ValueAnimator? = null
    private val total = 3800f

    private val skin = Color.rgb(242, 201, 160)
    private val ink = Color.rgb(40, 38, 35)
    private val cloth = Color.rgb(26, 26, 28)          // svarta kläder
    private val pants = Color.rgb(34, 34, 37)
    private val beard = Color.rgb(52, 36, 26)          // mörkt skägg
    private val shoe = Color.rgb(70, 50, 36)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = ink
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val path = Path()
    private val r = RectF()

    fun play() {
        if (anim?.isRunning == true) return
        val a = ValueAnimator.ofFloat(0f, total)
        a.duration = total.toLong()
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { v ->
            t = v.animatedValue as Float
            if (t >= total - 1f) t = -1f
            invalidate()
        }
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun easeOut(x: Float): Float { val c = x.coerceIn(0f, 1f); return 1f - (1f - c) * (1f - c) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (t < 0f) return
        val w = width.toFloat(); val h = height.toFloat()
        val size = min(w * 0.42f, 220f * d)          // gubbens höjd
        val u = size / 100f
        val baseY = h * 0.70f
        val standX = w * 0.66f
        val offX = w + 40f * u

        // --- tidslinje ---
        val walkIn = 1000f; val talkEnd = 3000f
        val x: Float
        val walking: Boolean
        when {
            t < walkIn -> { x = offX + (standX - offX) * easeOut(t / walkIn); walking = true }
            t < talkEnd -> { x = standX; walking = false }
            else -> { val k = (t - talkEnd) / (total - talkEnd); x = standX + (offX - standX) * k * k; walking = true }
        }
        val step = if (walking) sin(t / 1000f * 2f * PI.toFloat() * 2.6f) else 0f
        val bob = if (walking) -abs(step) * 2.5f * u else 0f
        val talking = t in walkIn..talkEnd
        val wave = if (talking) sin((t - walkIn) / 1000f * 2f * PI.toFloat() * 2.2f) else 0f
        val mouthOpen = talking && t < walkIn + 900f && ((t / 140f).toInt() % 2 == 0)

        c.save()
        c.translate(x, baseY + bob)

        // skugga
        fill.color = Color.argb(50, 0, 0, 0)
        r.set(-22 * u, -3 * u, 22 * u, 3 * u); c.drawOval(r, fill)

        // ben (svarta byxor)
        stroke.strokeWidth = 7 * u
        stroke.color = pants
        val hipY = -36 * u
        leg(c, -5 * u, hipY, step * 0.45f, u)
        stroke.color = pants
        leg(c, 5 * u, hipY, -step * 0.45f, u)

        // bakre arm, pendlar (svart ärm)
        stroke.color = cloth; stroke.strokeWidth = 5.5f * u
        arm(c, 12 * u, -64 * u, 0.15f - step * 0.5f, 26 * u)

        // kropp: svart arbetströja
        fill.color = cloth
        r.set(-15 * u, -72 * u, 15 * u, -34 * u); c.drawRoundRect(r, 7 * u, 7 * u, fill)
        // höger bröstficka (hans högra = vänster i bilden) med liten vit etikett, röd ram
        stroke.color = Color.rgb(70, 70, 74); stroke.strokeWidth = 0.9f * u
        r.set(-12.5f * u, -65 * u, -3 * u, -56 * u); c.drawRoundRect(r, 1.2f * u, 1.2f * u, stroke)
        c.drawLine(-12.5f * u, -62.5f * u, -3 * u, -62.5f * u, stroke)    // fickans lock
        fill.color = Color.WHITE
        r.set(-11 * u, -61.5f * u, -4.5f * u, -58 * u); c.drawRect(r, fill)
        stroke.color = Color.rgb(215, 30, 35); stroke.strokeWidth = 0.7f * u
        c.drawRect(r, stroke)

        // främre arm: vinkar när han pratar (svart ärm)
        stroke.color = cloth; stroke.strokeWidth = 5.5f * u
        if (talking) arm(c, -12 * u, -64 * u, (PI * 0.82).toFloat() + wave * 0.35f, 26 * u)
        else arm(c, -12 * u, -64 * u, -0.15f + step * 0.5f, 26 * u)

        // huvud
        fill.color = skin
        c.drawCircle(0f, -86 * u, 13 * u, fill)
        // mörkt helskägg: nedre halvan av ansiktet + polisonger
        fill.color = beard
        r.set(-13.8f * u, -99.8f * u, 13.8f * u, -71.2f * u); c.drawArc(r, 14f, 152f, false, fill)
        r.set(-14 * u, -93 * u, -10.5f * u, -84 * u); c.drawRoundRect(r, 1.5f * u, 1.5f * u, fill)
        r.set(10.5f * u, -93 * u, 14 * u, -84 * u); c.drawRoundRect(r, 1.5f * u, 1.5f * u, fill)
        // ögon
        fill.color = ink
        c.drawCircle(-6 * u, -86.5f * u, 1.8f * u, fill)
        c.drawCircle(3 * u, -86.5f * u, 1.8f * u, fill)
        // näsa
        fill.color = Color.rgb(226, 178, 135)
        c.drawCircle(-1.5f * u, -83.5f * u, 1.7f * u, fill)
        // mun i skägget
        if (mouthOpen) {
            fill.color = Color.rgb(120, 32, 36)
            r.set(-5 * u, -81.5f * u, 2 * u, -75.5f * u); c.drawOval(r, fill)
            stroke.color = Color.rgb(214, 130, 120); stroke.strokeWidth = 0.9f * u
            c.drawOval(r, stroke)
        } else {
            stroke.color = Color.rgb(214, 130, 120); stroke.strokeWidth = 1.4f * u
            r.set(-5.5f * u, -83 * u, 2.5f * u, -77.5f * u); c.drawArc(r, 25f, 130f, false, stroke)
        }
        // kort mörkt hår (samma färg som skägget)
        fill.color = beard
        r.set(-13.8f * u, -100.2f * u, 13.8f * u, -72.6f * u); c.drawArc(r, 192f, 156f, false, fill)
        c.restore()

        // pratbubbla
        if (talking) {
            val bt = t - walkIn
            val pop = when {
                bt < 180f -> bt / 180f * 1.12f
                bt < 300f -> 1.12f - (bt - 180f) / 120f * 0.12f
                t > talkEnd - 200f -> ((talkEnd - t) / 200f).coerceIn(0f, 1f)
                else -> 1f
            }
            if (pop > 0f) bubble(c, x - 22 * u, baseY - 112 * u, pop, u)
        }
    }

    private fun leg(c: Canvas, x: Float, y: Float, ang: Float, u: Float) {
        val len = 34 * u
        val fx = x + sin(ang) * len; val fy = y + cos(ang) * len
        c.drawLine(x, y, fx, fy, stroke)
        fill.color = shoe                                  // sko
        r.set(fx - 7 * u, fy - 2 * u, fx + 3 * u, fy + 3.5f * u); c.drawRoundRect(r, 2 * u, 2 * u, fill)
    }

    private fun arm(c: Canvas, x: Float, y: Float, ang: Float, len: Float) {
        val dir = if (x < 0) -1f else 1f
        val ex = x + sin(ang) * len * dir; val ey = y + cos(ang) * len
        c.drawLine(x, y, ex, ey, stroke)
        fill.color = skin
        c.drawCircle(ex, ey, stroke.strokeWidth * 0.75f, fill)
    }

    private fun bubble(c: Canvas, tipX: Float, tipY: Float, scale: Float, u: Float) {
        text.textSize = 30 * d
        val tw = text.measureText("Jubba!")
        val pw = tw + 32 * d; val ph = 58 * d
        val right = tipX - 4 * u; val left = right - pw
        val bottom = tipY - 6 * u; val top = bottom - ph
        val rad = 18 * d
        c.save()
        c.scale(scale, scale, tipX, tipY)
        fill.color = Color.argb(45, 0, 0, 0)
        r.set(left + 3 * d, top + 4 * d, right + 3 * d, bottom + 4 * d); c.drawRoundRect(r, rad, rad, fill)
        fill.color = Color.WHITE
        r.set(left, top, right, bottom); c.drawRoundRect(r, rad, rad, fill)
        // spets mot gubben
        val ax = right - 34 * d; val bx = right - 12 * d
        val px = tipX + 6 * u; val py = tipY + 6 * u
        path.reset(); path.moveTo(ax, bottom - 2 * d); path.lineTo(px, py); path.lineTo(bx, bottom - 2 * d); path.close()
        c.drawPath(path, fill)
        stroke.color = Color.rgb(29, 29, 27); stroke.strokeWidth = 2.5f * d
        r.set(left, top, right, bottom); c.drawRoundRect(r, rad, rad, stroke)
        c.drawLine(ax, bottom, px, py, stroke)
        c.drawLine(px, py, bx, bottom, stroke)
        fill.color = Color.WHITE                           // täck kantlinjen där spetsen sitter
        r.set(ax + 1.5f * d, bottom - 3.5f * d, bx - 1.5f * d, bottom + 1.5f * d); c.drawRect(r, fill)
        text.color = Color.rgb(232, 84, 28)
        c.drawText("Jubba!", (left + right) / 2, (top + bottom) / 2 - (text.ascent() + text.descent()) / 2, text)
        c.restore()
    }
}
