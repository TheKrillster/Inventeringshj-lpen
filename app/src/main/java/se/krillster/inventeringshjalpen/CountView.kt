package se.krillster.inventeringshjalpen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Mark(val x: Float, val y: Float, val auto: Boolean)

/** Visar bilden, låter användaren zooma/flytta, dra område/linje och lägga till/ta bort markeringar. */
class CountView(context: Context) : View(context) {

    enum class Tool { AREA, LINE, EDIT, REF }

    interface Listener {
        fun onArea(r: RectF)
        fun onLine(p0: Pt, p1: Pt)
        fun onMarksChanged()
        fun onRef(r: RectF) {}
    }

    var listener: Listener? = null
    var bitmap: Bitmap? = null
        set(v) { field = v; needFit = true; invalidate() }
    var tool = Tool.EDIT
    var sheetMode = false
    val marks = ArrayList<Mark>()
    var area: RectF? = null
    var line: Pair<Pt, Pt>? = null
    /** Markeringens diameter i bildpixlar. */
    var markSize = 20f
    var autoCount = 0

    private val undoStack = ArrayDeque<List<Mark>>()
    val canUndo get() = undoStack.isNotEmpty()

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var needFit = true

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillAuto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 232, 84, 28) }
    private val fillManual = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 31, 95, 191) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val shade = Paint().apply { color = Color.argb(115, 0, 0, 0) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(255, 210, 63) }

    // pågående drag
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var moved = false
    private var multi = false
    private var dragStart: Pt? = null
    private var dragEnd: Pt? = null

    private val scaleDet = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var fx = 0f; private var fy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { fx = d.focusX; fy = d.focusY; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = clampScale(scale * d.scaleFactor)
            val ix = (fx - tx) / scale; val iy = (fy - ty) / scale
            scale = ns
            tx = d.focusX - ix * scale; ty = d.focusY - iy * scale
            fx = d.focusX; fy = d.focusY
            invalidate(); return true
        }
    })

    private fun clampScale(s: Float): Float {
        val b = bitmap ?: return s
        val fit = min(width / b.width.toFloat(), height / b.height.toFloat())
        return s.coerceIn(fit * 0.5f, max(fit * 2f, 12f))
    }

    fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) { needFit = true; return }
        scale = min(width / b.width.toFloat(), height / b.height.toFloat()) * 0.97f
        tx = (width - b.width * scale) / 2f; ty = (height - b.height * scale) / 2f
        needFit = false; invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun toImg(x: Float, y: Float) = Pt((x - tx) / scale, (y - ty) / scale)

    // ---------- markeringar ----------
    fun pushUndo() {
        undoStack.addLast(ArrayList(marks))
        while (undoStack.size > 80) undoStack.removeFirst()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        marks.clear(); marks.addAll(undoStack.removeLast())
        invalidate(); listener?.onMarksChanged()
    }

    fun setAuto(points: List<Pt>) {
        pushUndo()
        marks.clear(); marks.addAll(points.map { Mark(it.x, it.y, true) })
        autoCount = points.size
        invalidate(); listener?.onMarksChanged()
    }

    fun reset() {
        marks.clear(); undoStack.clear(); area = null; line = null; autoCount = 0
        invalidate(); listener?.onMarksChanged()
    }

    private fun tap(sx: Float, sy: Float) {
        val b = bitmap ?: return
        val p = toImg(sx, sy)
        val hit = max(markSize * 0.55f, 18 * density / scale)
        var best = -1; var bd = Float.MAX_VALUE
        marks.forEachIndexed { i, m -> val d = hypot(m.x - p.x, m.y - p.y); if (d < bd) { bd = d; best = i } }
        if (best >= 0 && bd < hit) {
            pushUndo(); marks.removeAt(best)
        } else {
            if (p.x < 0 || p.y < 0 || p.x > b.width || p.y > b.height) return
            var q = p
            val ln = line
            if (sheetMode && ln != null) { // lägg nya streck på linjen
                val (a, c) = ln
                val dx = c.x - a.x; val dy = c.y - a.y; val l2 = dx * dx + dy * dy
                if (l2 > 1f) {
                    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
                    q = Pt(a.x + dx * t, a.y + dy * t)
                }
            }
            pushUndo(); marks.add(Mark(q.x, q.y, false))
        }
        invalidate(); listener?.onMarksChanged()
    }

    fun numbers(): IntArray {
        val pts = marks.map { Pt(it.x, it.y) }
        val ln = line
        return if (sheetMode && ln != null) Numbering.alongLine(pts, ln.first, ln.second)
        else Numbering.byRows(pts, max(4f, markSize * 0.5f))
    }

    // ---------- touch ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; multi = false
                if (tool != Tool.EDIT) { dragStart = toImg(e.x, e.y); dragEnd = dragStart }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multi = true; dragStart = null; dragEnd = null; invalidate() }
            MotionEvent.ACTION_MOVE -> {
                if (!multi && !scaleDet.isInProgress) {
                    if (hypot(e.x - downX, e.y - downY) > slop) moved = true
                    if (dragStart != null) {
                        if (moved) { dragEnd = toImg(e.x, e.y); invalidate() }
                    } else if (moved) {
                        tx += e.x - lastX; ty += e.y - lastY; invalidate()
                    }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val s = dragStart; val en = dragEnd
                if (!multi) {
                    if (s != null && en != null && moved) finishDrag(s, en)
                    else if (!moved && tool == Tool.EDIT) tap(e.x, e.y)
                }
                dragStart = null; dragEnd = null; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragStart = null; dragEnd = null; invalidate() }
        }
        return true
    }

    private fun finishDrag(s: Pt, e: Pt) {
        val b = bitmap ?: return
        val minSize = 24 * density / scale
        if (tool == Tool.REF) {
            val r = RectF(min(s.x, e.x), min(s.y, e.y), max(s.x, e.x), max(s.y, e.y))
            if (r.width() > minSize / 3 && r.height() > minSize / 3) listener?.onRef(r)
        } else if (tool == Tool.AREA) {
            val r = RectF(min(s.x, e.x).coerceAtLeast(0f), min(s.y, e.y).coerceAtLeast(0f),
                max(s.x, e.x).coerceAtMost(b.width.toFloat()), max(s.y, e.y).coerceAtMost(b.height.toFloat()))
            if (r.width() > minSize && r.height() > minSize) { area = r; listener?.onArea(r) }
        } else if (tool == Tool.LINE) {
            if (hypot(e.x - s.x, e.y - s.y) > minSize) {
                val clamp = { p: Pt -> Pt(p.x.coerceIn(0f, b.width - 1f), p.y.coerceIn(0f, b.height - 1f)) }
                val l = Pair(clamp(s), clamp(e)); line = l; listener?.onLine(l.first, l.second)
            }
        }
    }

    // ---------- ritning ----------
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.rgb(42, 41, 38))
        val b = bitmap ?: return
        if (needFit) fit()
        c.save()
        c.translate(tx, ty); c.scale(scale, scale)
        c.drawBitmap(b, 0f, 0f, bmpPaint)
        val px = 1f / scale
        area?.let { r ->
            c.drawRect(0f, 0f, b.width.toFloat(), r.top, shade)
            c.drawRect(0f, r.bottom, b.width.toFloat(), b.height.toFloat(), shade)
            c.drawRect(0f, r.top, r.left, r.bottom, shade)
            c.drawRect(r.right, r.top, b.width.toFloat(), r.bottom, shade)
            guide.strokeWidth = 2 * density * px
            guide.pathEffect = DashPathEffect(floatArrayOf(8 * density * px, 6 * density * px), 0f)
            c.drawRect(r, guide)
            guide.pathEffect = null
        }
        line?.let { (a, e) ->
            guide.strokeWidth = 2 * density * px
            c.drawLine(a.x, a.y, e.x, e.y, guide)
        }
        val ds = dragStart; val de = dragEnd
        if (ds != null && de != null && moved) {
            guide.strokeWidth = 2 * density * px
            if (tool == Tool.AREA || tool == Tool.REF) c.drawRect(min(ds.x, de.x), min(ds.y, de.y), max(ds.x, de.x), max(ds.y, de.y), guide)
            else c.drawLine(ds.x, ds.y, de.x, de.y, guide)
        }
        drawMarks(c, scale)
        c.restore()
    }

    /** Ritar markeringar i bildkoordinater. pxScale = skärmpixlar per bildpixel (för minsta storlek/text). */
    fun drawMarks(c: Canvas, pxScale: Float) {
        if (marks.isEmpty()) return
        val num = numbers()
        val ln = line
        if (sheetMode && ln != null) { drawTicks(c, pxScale, num, ln); return }
        val r = max(3 * density / pxScale, markSize * 0.42f)
        val showNum = r * pxScale >= 7 * density
        ring.strokeWidth = max(1f / pxScale, r * 0.12f)
        marks.forEachIndexed { i, m ->
            c.drawCircle(m.x, m.y, r, if (m.auto) fillAuto else fillManual)
            c.drawCircle(m.x, m.y, r, ring)
            if (showNum) {
                val t = num[i].toString()
                text.textSize = when { t.length >= 4 -> r * 0.68f; t.length == 3 -> r * 0.8f; else -> r * 1.05f }
                c.drawText(t, m.x, m.y - (text.ascent() + text.descent()) / 2, text)
            }
        }
    }

    /** Skivor: ett streck tvärs linjen per skiva, nummer vid sidan. */
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val numBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
    private fun drawTicks(c: Canvas, pxScale: Float, num: IntArray, ln: Pair<Pt, Pt>) {
        val (a, e) = ln
        val len = hypot(e.x - a.x, e.y - a.y).coerceAtLeast(1f)
        val nx = -(e.y - a.y) / len; val ny = (e.x - a.x) / len // vinkelrätt mot linjen
        val pitch = markSize / 0.85f
        val half = max(pitch * 1.6f, 10 * density / pxScale)
        tickPaint.strokeWidth = max(1.5f / pxScale, pitch * 0.22f)
        val onScreen = pitch * pxScale
        val every = when { onScreen >= 9 * density -> 1; onScreen >= 4 * density -> 5; else -> 10 }
        text.textSize = max(pitch * 0.9f, 9 * density / pxScale).coerceAtMost(pitch * every * 0.9f)
        marks.forEachIndexed { i, m ->
            tickPaint.color = if (m.auto) fillAuto.color else fillManual.color
            c.drawLine(m.x - nx * half, m.y - ny * half, m.x + nx * half, m.y + ny * half, tickPaint)
            val n = num[i]
            if (n % every == 0 || n == 1 || n == marks.size) {
                val t = n.toString()
                // varannan siffra på var sin sida så att de inte krockar
                val side = if (every == 1 && n % 2 == 1) -1f else 1f
                val off = side * (half + text.textSize * 1.4f)
                val tx0 = m.x + nx * off
                val ty0 = m.y + ny * off
                val w = text.measureText(t) / 2 + text.textSize * 0.25f
                c.drawRoundRect(tx0 - w, ty0 - text.textSize * 0.62f, tx0 + w, ty0 + text.textSize * 0.62f,
                    text.textSize * 0.3f, text.textSize * 0.3f, numBg)
                c.drawText(t, tx0, ty0 - (text.ascent() + text.descent()) / 2, text)
            }
        }
    }

    /** Skapar en bild med markeringar och en rubrikrad, beskuren till området. */
    fun renderExport(header1: String, header2: String): Bitmap? {
        val b = bitmap ?: return null
        val pad = markSize * 1.5f
        var crop = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        area?.let { crop = RectF(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
        line?.let { (a, e) ->
            if (sheetMode) crop = RectF(min(a.x, e.x) - pad * 6, min(a.y, e.y) - pad * 2, max(a.x, e.x) + pad * 6, max(a.y, e.y) + pad * 2)
        }
        if (marks.isNotEmpty() && area == null && !(sheetMode && line != null)) {
            crop = RectF(marks.minOf { it.x } - pad * 2, marks.minOf { it.y } - pad * 2, marks.maxOf { it.x } + pad * 2, marks.maxOf { it.y } + pad * 2)
        }
        crop.intersect(0f, 0f, b.width.toFloat(), b.height.toFloat())
        val s = min(2.5f, 2400f / max(crop.width(), crop.height()))
        val head = 120
        val out = Bitmap.createBitmap((crop.width() * s).toInt().coerceAtLeast(600), (crop.height() * s).toInt() + head, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(29, 29, 27))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f; isFakeBoldText = true }
        c.drawText(header1, 24f, 52f, tp)
        tp.textSize = 32f; tp.isFakeBoldText = false; tp.color = Color.rgb(220, 220, 215)
        c.drawText(header2, 24f, 100f, tp)
        c.save()
        c.translate(0f, head.toFloat())
        c.scale(s, s)
        c.translate(-crop.left, -crop.top)
        c.clipRect(crop)
        c.drawBitmap(b, 0f, 0f, bmpPaint)
        line?.let { (a, e) -> guide.strokeWidth = 2f / s; c.drawLine(a.x, a.y, e.x, e.y, guide) }
        drawMarks(c, s * 3f) // alltid nummer i exporten
        c.restore()
        return out
    }
}
