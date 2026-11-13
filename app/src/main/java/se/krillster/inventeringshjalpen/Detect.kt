package se.krillster.inventeringshjalpen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Ren Kotlin (inga Android-beroenden) så att algoritmerna kan testas på datorn. */
data class Pt(val x: Float, val y: Float)

class GrayImage(val w: Int, val h: Int, val px: FloatArray) {
    operator fun get(x: Int, y: Int): Float = px[y * w + x]
    fun bilinear(x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat())
        val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(xc).toInt(); val y0 = floor(yc).toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xc - x0; val fy = yc - y0
        val a = px[y0 * w + x0] * (1 - fx) + px[y0 * w + x1] * fx
        val b = px[y1 * w + x0] * (1 - fx) + px[y1 * w + x1] * fx
        return a * (1 - fy) + b * fy
    }
}

class CircleResult(val points: List<Pt>, val diameter: Float, val singleArea: Float = 0f)
class SheetResult(val points: List<Pt>, val pitch: Float)

object CircleDetector {
    private class Comp(val a: Int, val cx: Float, val cy: Float, val bw: Int, val bh: Int, val mi: Float)

    /**
     * Hittar mörka, runda hål (rörändar) inom området [x0,y0,x1,y1].
     * sens 1..10, högre = hittar fler (svagare kontrast godtas).
     */
    fun detect(img: GrayImage, rx0: Float, ry0: Float, rx1: Float, ry1: Float, sens: Int): CircleResult {
        val x0 = max(0, floor(min(rx0, rx1)).toInt()); val y0 = max(0, floor(min(ry0, ry1)).toInt())
        val x1 = min(img.w, ceil(max(rx0, rx1)).toInt()); val y1 = min(img.h, ceil(max(ry0, ry1)).toInt())
        val cw = x1 - x0; val ch = y1 - y0
        if (cw < 20 || ch < 20) return CircleResult(emptyList(), 0f)
        val sub = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) sub[y * cw + x] = img.px[(y + y0) * img.w + x + x0]
        val iw = cw + 1
        val integ = DoubleArray(iw * (ch + 1))
        for (y in 0 until ch) {
            var rs = 0.0
            for (x in 0 until cw) { rs += sub[y * cw + x]; integ[(y + 1) * iw + x + 1] = integ[y * iw + x + 1] + rs }
        }
        fun mean(x: Int, y: Int, k: Int): Float {
            val a = max(0, x - k); val b = max(0, y - k); val c = min(cw, x + k + 1); val d = min(ch, y + k + 1)
            val s = integ[d * iw + c] - integ[b * iw + c] - integ[d * iw + a] + integ[b * iw + a]
            return (s / ((c - a) * (d - b))).toFloat()
        }
        val c = 4f + (10 - sens.coerceIn(1, 10)) * 1.6f

        fun pass(win: Float): List<Comp> {
            val k = max(3, (win / 2).roundToInt())
            val m = ByteArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                val i = y * cw + x
                if (sub[i] < mean(x, y, k) - c) m[i] = 1
            }
            val er = ByteArray(cw * ch)
            for (y in 1 until ch - 1) for (x in 1 until cw - 1) {
                val i = y * cw + x
                if (m[i].toInt() == 1 && m[i - 1].toInt() == 1 && m[i + 1].toInt() == 1 && m[i - cw].toInt() == 1 &&
                    m[i + cw].toInt() == 1 && m[i - cw - 1].toInt() == 1 && m[i - cw + 1].toInt() == 1 &&
                    m[i + cw - 1].toInt() == 1 && m[i + cw + 1].toInt() == 1) er[i] = 1
            }
            val op = ByteArray(cw * ch)
            for (y in 1 until ch - 1) for (x in 1 until cw - 1) {
                val i = y * cw + x
                if (er[i].toInt() == 1 || er[i - 1].toInt() == 1 || er[i + 1].toInt() == 1 || er[i - cw].toInt() == 1 ||
                    er[i + cw].toInt() == 1 || er[i - cw - 1].toInt() == 1 || er[i - cw + 1].toInt() == 1 ||
                    er[i + cw - 1].toInt() == 1 || er[i + cw + 1].toInt() == 1) op[i] = 1
            }
            val lab = IntArray(cw * ch)
            val stack = IntArray(cw * ch)
            val comps = ArrayList<Comp>()
            for (i in op.indices) {
                if (op[i].toInt() == 0 || lab[i] != 0) continue
                val n = comps.size + 1
                var sp = 0; var a = 0; var sx = 0.0; var sy = 0.0; var si = 0.0
                var mnx = Int.MAX_VALUE; var mny = Int.MAX_VALUE; var mxx = -1; var mxy = -1
                stack[sp++] = i; lab[i] = n
                while (sp > 0) {
                    val j = stack[--sp]
                    val x = j % cw; val y = j / cw
                    a++; sx += x; sy += y; si += sub[j]
                    if (x < mnx) mnx = x; if (x > mxx) mxx = x; if (y < mny) mny = y; if (y > mxy) mxy = y
                    if (x > 0 && op[j - 1].toInt() == 1 && lab[j - 1] == 0) { lab[j - 1] = n; stack[sp++] = j - 1 }
                    if (x < cw - 1 && op[j + 1].toInt() == 1 && lab[j + 1] == 0) { lab[j + 1] = n; stack[sp++] = j + 1 }
                    if (y > 0 && op[j - cw].toInt() == 1 && lab[j - cw] == 0) { lab[j - cw] = n; stack[sp++] = j - cw }
                    if (y < ch - 1 && op[j + cw].toInt() == 1 && lab[j + cw] == 0) { lab[j + cw] = n; stack[sp++] = j + cw }
                }
                comps.add(Comp(a, (sx / a).toFloat(), (sy / a).toFloat(), mxx - mnx + 1, mxy - mny + 1, (si / a).toFloat()))
            }
            return comps
        }

        // Typisk hålstorlek: ytviktad median av runda, mörka fläckar. Ytviktningen gör att de
        // stora rörhålen vinner över de många små mörka mellanrummen mellan rören.
        fun typicalArea(cs: List<Comp>, minArea: Float): Float? {
            val ok = cs.filter {
                it.a > minArea && max(it.bw, it.bh).toFloat() / max(1, min(it.bw, it.bh)) <= 2.2f &&
                    it.a.toFloat() / (it.bw * it.bh) >= 0.35f
            }.map { it.a }.sorted()
            if (ok.isEmpty()) return null
            val trimmed = ok.subList(0, max(1, (ok.size * 0.98).toInt())) // bort med enstaka jätteklumpar
            val total = trimmed.sumOf { it.toLong() }
            var acc = 0L
            for (a in trimmed) { acc += a; if (acc * 2 >= total) return a.toFloat() }
            return trimmed.last().toFloat()
        }

        var comps = pass(min(cw, ch) / 18f)
        var med = typicalArea(comps, 8f) ?: return CircleResult(emptyList(), 0f)
        var d = 2f * sqrt(med / PI.toFloat())
        comps = pass(max(9f, d * 3.2f))
        med = typicalArea(comps, max(6f, med * 0.15f)) ?: return CircleResult(emptyList(), 0f)
        d = 2f * sqrt(med / PI.toFloat())

        // antal av 16 punkter på en ring runt hålet som är tydligt ljusare än hålet
        fun ring(cx: Float, cy: Float, rr: Float, mi: Float): Int {
            var k = 0
            for (q in 0 until 16) {
                val an = q * PI / 8
                val x = (cx + cos(an) * rr).roundToInt(); val y = (cy + sin(an) * rr).roundToInt()
                if (x < 0 || y < 0 || x >= cw || y >= ch) continue
                if (sub[y * cw + x] - mi > c * 1.2f) k++
            }
            return k
        }

        val out = ArrayList<Pt>()
        for (o in comps) {
            val asp = max(o.bw, o.bh).toFloat() / max(1, min(o.bw, o.bh))
            val fill = o.a.toFloat() / (o.bw * o.bh)
            if (o.a < med * 0.33f) continue
            val req = sqrt(o.a / PI.toFloat())
            if (ring(o.cx, o.cy, req * 1.3f, o.mi) < 11) continue
            if ((o.a <= med * 1.7f && asp <= 1.8f && fill >= 0.45f) || (o.a <= med * 2.4f && asp <= 1.35f && fill >= 0.6f)) {
                out.add(Pt(o.cx, o.cy)); continue
            }
            if (o.a > med * 1.5f && o.a <= med * 2.7f && asp > 1.4f) { // två hål som flutit ihop
                val horiz = o.bw >= o.bh
                val off = (if (horiz) o.bw else o.bh) / 4f
                out.add(Pt(o.cx - (if (horiz) off else 0f), o.cy - (if (horiz) 0f else off)))
                out.add(Pt(o.cx + (if (horiz) off else 0f), o.cy + (if (horiz) 0f else off)))
            }
        }
        val res = ArrayList<Pt>()
        val md2 = (d * 0.55f) * (d * 0.55f)
        for (p in out) {
            if (res.none { (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) < md2 }) res.add(p)
        }
        val filled = fillByRingPattern(res, d, cw, ch, c, ::mean)
        return CircleResult(filled.map { Pt(it.x + x0, it.y + y0) }, d)
    }

    /**
     * Steg 2: rör där hålet inte syns som en tydlig mörk fläck (upplyst insida, snett ljus).
     * Med känt avstånd mellan rören letar vi efter mönstret "ljus rörvägg runt en mörkare mitt"
     * på platser där inget rör redan hittats.
     */
    private fun fillByRingPattern(found: List<Pt>, d: Float, cw: Int, ch: Int, c: Float,
                                  mean: (Int, Int, Int) -> Float): List<Pt> {
        if (found.size < 12) return found
        // avstånd mellan rörens mittpunkter = median av närmaste-granne-avstånd
        val cell = max(4f, d * 2f)
        val grid = HashMap<Long, MutableList<Pt>>()
        fun key(x: Float, y: Float) = ((x / cell).toInt().toLong() shl 32) or ((y / cell).toInt().toLong() and 0xffffffffL)
        fun add(p: Pt) { grid.getOrPut(key(p.x, p.y)) { ArrayList() }.add(p) }
        fun near(x: Float, y: Float, r: Float): Float {
            var best = Float.MAX_VALUE
            val gx = (x / cell).toInt(); val gy = (y / cell).toInt(); val span = (r / cell).toInt() + 1
            for (ix in gx - span..gx + span) for (iy in gy - span..gy + span) {
                val l = grid[(ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)] ?: continue
                for (q in l) { val dd = hypot(q.x - x, q.y - y); if (dd < best) best = dd }
            }
            return best
        }
        found.forEach { add(it) }
        val nn = found.map { p ->
            var best = Float.MAX_VALUE
            for (q in found) if (q !== p) { val dd = hypot(q.x - p.x, q.y - p.y); if (dd < best && dd > 0.5f) best = dd }
            best
        }.filter { it < d * 6 }.sorted()
        if (nn.size < 8) return found
        val pitch = nn[nn.size / 2]
        if (pitch < d * 1.05f) return found
        val rWall = pitch / 2                    // yttersta radien för väggen
        val r0 = min(d / 2 * 0.95f, pitch / 2 * 0.85f)
        val radii = FloatArray(5) { r0 + (pitch / 2 * 1.02f - r0) * it / 4f }
        val rIn = d * 0.22f

        val OUTSIDE = -998f
        var lastInner = 0f; var lastRing = 0f; var lastMinQuad = 0f
        fun score(x: Float, y: Float): Float {
            val xi = x.roundToInt(); val yi = y.roundToInt()
            if (xi - rWall * 1.05f < 1 || yi - rWall * 1.05f < 1 || xi + rWall * 1.05f > cw - 2 || yi + rWall * 1.05f > ch - 2) return OUTSIDE
            var inner = mean(xi, yi, 1)
            for (q in 0 until 6) { val a = q * PI / 3; inner += mean((x + cos(a) * rIn).roundToInt(), (y + sin(a) * rIn).roundToInt(), 1) }
            inner /= 7f
            val vals = FloatArray(16)
            val quad = FloatArray(4)
            var ring = 0f
            for (q in 0 until 16) {
                val a = q * PI / 8
                // ljusaste punkten mellan hålkant och rörets ytterkant (tunna väggar missas annars)
                var v = -1f
                for (r in radii) {
                    val m = mean((x + cos(a) * r).roundToInt(), (y + sin(a) * r).roundToInt(), 0)
                    if (m > v) v = m
                }
                vals[q] = v; ring += v
                quad[((q + 2) / 4) % 4] += v / 4f
            }
            ring /= 16f
            // relativt mot den egna omgivningen, så att skuggiga delar av bilden också fungerar
            val t = max(4f, (ring - inner) * 0.4f)
            var bright = 0
            for (v in vals) if (v - inner > t) bright++
            lastInner = inner; lastRing = ring; lastMinQuad = quad.min()
            if (ring - inner < c * 0.8f) return -999f
            return if (bright >= 12) ring - inner else -999f
        }

        // referens: hur starkt mönstret är på redan hittade rör
        val refIn = ArrayList<Float>(); val refRing = ArrayList<Float>()
        val ref = found.map { val v = score(it.x, it.y); if (v > -998f) { refIn.add(lastInner); refRing.add(lastRing) }; v }
            .filter { it > -998f }.sorted()
        if (ref.size < 8) return found
        refIn.sort(); refRing.sort()
        val inMed = refIn[refIn.size / 2]; val ringMed = refRing[refRing.size / 2]
        // mitten måste vara klart mörkare än en typisk rörvägg (planka/kant är ljus rakt igenom)
        val maxInner = inMed + (ringMed - inMed) * 0.55f
        val thr = max(c, ref[ref.size / 4] * 0.3f)

        val step = max(1, (pitch / 14).roundToInt())
        val gw = cw / step + 1; val gh = ch / step + 1
        val sc = FloatArray(gw * gh) { -999f }
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x = (gx * step).toFloat(); val y = (gy * step).toFloat()
            if (near(x, y, pitch * 0.7f) < pitch * 0.7f) continue
            val v = score(x, y).let { if (it == OUTSIDE) -999f else it }
            // hela väggen runt om ska vara ljus (inte bara planka på ena sidan och rör på den andra)
            val wallOk = lastMinQuad - lastInner >= (lastRing - lastInner) * 0.4f
            sc[gy * gw + gx] = if (v > -999f && lastInner <= maxInner && wallOk) v else -999f
        }
        // lokala toppar
        val rad = max(1, (pitch * 0.35f / step).roundToInt())
        val cands = ArrayList<Triple<Float, Float, Float>>()
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val v = sc[gy * gw + gx]
            if (v < thr) continue
            var isMax = true
            loop@ for (dy in -rad..rad) for (dx in -rad..rad) {
                val xx = gx + dx; val yy = gy + dy
                if (xx < 0 || yy < 0 || xx >= gw || yy >= gh || (dx == 0 && dy == 0)) continue
                if (sc[yy * gw + xx] > v) { isMax = false; break@loop }
            }
            if (isMax) cands.add(Triple(v, (gx * step).toFloat(), (gy * step).toFloat()))
        }
        cands.sortByDescending { it.first }
        val out = ArrayList(found)
        // Ett nytt rör måste passa i packningen: minst två grannar på ungefär ett röravstånd.
        fun neighbours(x: Float, y: Float): Int {
            var n = 0
            val gx = (x / cell).toInt(); val gy = (y / cell).toInt(); val span = (pitch * 1.25f / cell).toInt() + 1
            for (ix in gx - span..gx + span) for (iy in gy - span..gy + span) {
                val l = grid[(ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)] ?: continue
                for (q in l) { val dd = hypot(q.x - x, q.y - y); if (dd > pitch * 0.82f && dd < pitch * 1.2f) n++ }
            }
            return n
        }
        val used = BooleanArray(cands.size)
        var changed = true; var rounds = 0
        while (changed && rounds < 60) {
            changed = false; rounds++
            for ((i, cnd) in cands.withIndex()) {
                if (used[i]) continue
                val (_, x, y) = cnd
                if (near(x, y, pitch * 0.75f) < pitch * 0.75f) { used[i] = true; continue }
                if (neighbours(x, y) < 2) continue
                val p = Pt(x, y); out.add(p); add(p); used[i] = true; changed = true
            }
        }

        // Hur långt ut från mitten det är mörkt, i 8 riktningar. I ett rörhål tar mörkret slut vid väggen;
        // i en glipa mot planka/vägg läcker det ut åt sidan.
        fun darkLeaks(x: Float, y: Float, inner: Float, ring: Float): Boolean {
            val lim = inner + (ring - inner) * 0.5f
            val maxR = pitch * 0.75f
            for (q in 0 until 8) {
                val a = q * PI / 4
                var r = 1f
                var leaked = true
                while (r < maxR) {
                    val v = mean((x + cos(a) * r).roundToInt().coerceIn(0, cw - 1), (y + sin(a) * r).roundToInt().coerceIn(0, ch - 1), 0)
                    if (v > lim) { leaked = false; break }
                    r += 1f
                }
                if (leaked) return true
            }
            return false
        }

        // Slutkontroll av ALLA markeringar: rörmönster runt om, och aldrig två närmare än ett rör.
        // Tar bort träffar i glipor mot väggar och plankor.
        class Sc(val p: Pt, val v: Float)
        val checked = ArrayList<Sc>()
        for (p in out) {
            val v = score(p.x, p.y)
            if (v == OUTSIDE) { checked.add(Sc(p, 0f)); continue }   // vid kanten av rutan: behåll
            val ok = v > -999f && lastInner <= maxInner && (lastMinQuad - lastInner) >= (lastRing - lastInner) * 0.4f &&
                !darkLeaks(p.x, p.y, lastInner, lastRing)
            if (ok) checked.add(Sc(p, v))
        }
        checked.sortByDescending { it.v }
        grid.clear()
        val final = ArrayList<Pt>()
        for (sc in checked) {
            if (near(sc.p.x, sc.p.y, pitch * 0.78f) < pitch * 0.78f) continue
            final.add(sc.p); add(sc.p)
        }
        return final
    }
}

object SheetDetector {
    /**
     * Räknar skivor/lager längs en linje dragen tvärs över stapeln (från översta till understa skivan).
     * Profilen tas längs linjen och medelvärdesbildas längs skivornas egen riktning.
     * sens 1..10, högre = fler streck godtas.
     */
    /**
     * pitchHint: om användaren tryckt Tätare/Glesare – ungefärligt avstånd mellan skivor i pixlar.
     */
    fun detect(img: GrayImage, p0: Pt, p1: Pt, sens: Int, pitchHint: Float? = null): SheetResult {
        val len = hypot(p1.x - p0.x, p1.y - p0.y)
        val n = len.toInt()
        if (n < 20) return SheetResult(emptyList(), 0f)
        val ux = (p1.x - p0.x) / len; val uy = (p1.y - p0.y) / len
        val half = max(16, (img.w.coerceAtLeast(img.h) / 60f).roundToInt())
        val pr = FloatArray(n)
        var vx = -uy; var vy = ux // standard: vinkelrätt mot linjen
        for (t in 0 until n) {
            val cx = p0.x + ux * t; val cy = p0.y + uy * t
            if (t % 20 == 0) {
                val th = orientation(img, cx, cy, 22)
                if (th != null) { vx = cos(th).toFloat(); vy = sin(th).toFloat() }
            }
            var s = 0f
            for (k in -half..half) s += img.bilinear(cx + vx * k, cy + vy * k)
            pr[t] = s / (2 * half + 1)
        }
        // ta bort långsam ljusvariation och jämna ut lite
        val bg = movingAverage(pr, 41)
        val raw = FloatArray(n) { pr[it] - bg[it] }
        val d = gaussian(raw, 1.2f)
        val global = pitchHint?.roundToInt()?.coerceAtLeast(3) ?: (firstPeriod(d) ?: 10)
        // Lokalt avstånd längs linjen (perspektiv gör skivorna tunnare längre bort)
        val local = localPeriods(d, global.toFloat())
        val lag = global
        val f = (0.75f - sens.coerceIn(1, 10) * 0.03f)
        val mins = ArrayList<Int>()
        for (i in 1 until n - 1) {
            if (d[i] < d[i - 1] && d[i] <= d[i + 1]) {
                val md = f * local[i]
                if (mins.isNotEmpty() && i - mins.last() < md) {
                    if (d[i] < d[mins.last()]) mins[mins.size - 1] = i
                } else mins.add(i)
            }
        }
        return SheetResult(mins.map { Pt(p0.x + ux * it, p0.y + uy * it) }, lag.toFloat())
    }

    /** Period i fönster längs profilen, sökt nära den globala perioden, sedan medianfiltrerad. */
    private fun localPeriods(d: FloatArray, global: Float): FloatArray {
        val n = d.size
        val win = max(120, (global * 10).toInt())
        val out = FloatArray(n) { global }
        if (n < win) return out
        val centers = ArrayList<Int>(); val vals = ArrayList<Float>()
        var s = 0
        while (s + win <= n) {
            val lo = max(3, (global * 0.7f).toInt()); val hi = min(win / 3, (global * 1.45f).toInt() + 1)
            if (hi > lo + 1) {
                val seg = d.copyOfRange(s, s + win)
                val m = seg.average().toFloat()
                val ac = DoubleArray(hi + 2)
                for (lag in 0..hi + 1) { var t = 0.0; for (i in 0 until win - lag) t += (seg[i] - m) * (seg[i + lag] - m); ac[lag] = t }
                var best = -1; var bv = Double.NEGATIVE_INFINITY
                for (lag in lo..hi) if (ac[lag] > ac[lag - 1] && ac[lag] >= ac[lag + 1] && ac[lag] > bv) { bv = ac[lag]; best = lag }
                if (best > 0 && bv > 0) { centers.add(s + win / 2); vals.add(best.toFloat()) }
            }
            s += win / 2
        }
        if (vals.isEmpty()) return out
        // medianfilter över 3 fönster, sedan linjär interpolation
        val sm = vals.indices.map { i -> listOf(vals[max(0, i - 1)], vals[i], vals[min(vals.size - 1, i + 1)]).sorted()[1] }
        for (i in 0 until n) {
            val k = centers.indexOfFirst { it >= i }
            out[i] = when {
                k == -1 -> sm.last()
                k == 0 -> sm.first()
                else -> { val a = centers[k - 1]; val b = centers[k]; val t = (i - a).toFloat() / (b - a); sm[k - 1] * (1 - t) + sm[k] * t }
            }
        }
        return out
    }

    /** Riktning (radianer) längs kanterna kring (x,y), via strukturtensor. */
    private fun orientation(img: GrayImage, x: Float, y: Float, r: Int): Double? {
        var jxx = 0.0; var jyy = 0.0; var jxy = 0.0
        val xi = x.roundToInt(); val yi = y.roundToInt()
        var yy = yi - r
        while (yy <= yi + r) {
            var xx = xi - r
            while (xx <= xi + r) {
                if (xx > 0 && yy > 0 && xx < img.w - 1 && yy < img.h - 1) {
                    val gx = (img[xx + 1, yy] - img[xx - 1, yy]).toDouble()
                    val gy = (img[xx, yy + 1] - img[xx, yy - 1]).toDouble()
                    jxx += gx * gx; jyy += gy * gy; jxy += gx * gy
                }
                xx += 2
            }
            yy += 2
        }
        if (jxx + jyy < 1e-6) return null
        return 0.5 * atan2(2 * jxy, jxx - jyy) + PI / 2
    }

    private fun movingAverage(a: FloatArray, win: Int): FloatArray {
        val n = a.size; val h = win / 2
        val cs = DoubleArray(n + 1)
        for (i in 0 until n) cs[i + 1] = cs[i] + a[i]
        return FloatArray(n) { i ->
            val lo = max(0, i - h); val hi = min(n, i + h + 1)
            ((cs[hi] - cs[lo]) / (hi - lo)).toFloat()
        }
    }

    private fun gaussian(a: FloatArray, sigma: Float): FloatArray {
        val r = ceil(sigma * 3).toInt()
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val n = a.size
        return FloatArray(n) { i ->
            var s = 0f; var w = 0f
            for (j in -r..r) { val q = i + j; if (q in 0 until n) { s += a[q] * k[j + r]; w += k[j + r] } }
            s / w
        }
    }

    /** Första tydliga topp i autokorrelationen = avstånd mellan skivor i pixlar. */
    private fun firstPeriod(d: FloatArray): Int? {
        val n = d.size
        val m = d.average().toFloat()
        val z = FloatArray(n) { d[it] - m }
        val maxLag = min(80, n / 3)
        if (maxLag < 6) return null
        val ac = DoubleArray(maxLag + 2)
        for (lag in 0..maxLag + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += z[i] * z[i + lag]
            ac[lag] = s
        }
        if (ac[0] <= 0) return null
        // Starkaste toppen = avståndet mellan skivor. Wellpappens vågor ger svagare toppar på
        // kortare avstånd; en kortare topp väljs bara om den är nästan lika stark.
        val peaks = (4..maxLag).filter { ac[it] > ac[it - 1] && ac[it] >= ac[it + 1] && ac[it] / ac[0] > 0.03 }
        if (peaks.isEmpty()) return null
        val best = peaks.maxByOrNull { ac[it] }!!
        val shorter = peaks.filter { it < best && ac[it] >= ac[best] * 0.7 }
        return shorter.minOrNull() ?: best
    }
}

object Numbering {
    /** Numrering rad för rad (uppifrån, vänster→höger). Returnerar nummer per index. */
    fun byRows(pts: List<Pt>, rowTol: Float): IntArray {
        val order = pts.indices.sortedBy { pts[it].y }
        val rows = ArrayList<MutableList<Int>>()
        val rowY = ArrayList<Float>()
        for (i in order) {
            val y = pts[i].y
            if (rows.isNotEmpty() && abs(y - rowY.last()) < rowTol) {
                val r = rows.last(); r.add(i)
                rowY[rowY.size - 1] = (rowY.last() * (r.size - 1) + y) / r.size
            } else { rows.add(mutableListOf(i)); rowY.add(y) }
        }
        val num = IntArray(pts.size)
        var k = 1
        for (r in rows) for (i in r.sortedBy { pts[it].x }) num[i] = k++
        return num
    }

    /** Numrering längs en linje (för skivor). */
    fun alongLine(pts: List<Pt>, p0: Pt, p1: Pt): IntArray {
        val dx = p1.x - p0.x; val dy = p1.y - p0.y
        val order = pts.indices.sortedBy { (pts[it].x - p0.x) * dx + (pts[it].y - p0.y) * dy }
        val num = IntArray(pts.size)
        order.forEachIndexed { k, i -> num[i] = k + 1 }
        return num
    }
}

class ColorImage(val w: Int, val h: Int, val px: IntArray)

/**
 * Lösa småsaker (skruv, mutter, bricka, O-ring …) på ett någorlunda jämnt underlag.
 * Allt som skiljer sig i färg/ljus från underlaget räknas som föremål. Klumpar där flera
 * ligger ihop delas upp utifrån ytan av ett typiskt enstaka föremål.
 */
object LooseDetector {
    /**
     * ref: ruta (bildkoordinater) runt EN sak som användaren visat – ger exakt storlek på ett föremål.
     */
    fun detect(img: ColorImage, rx0: Float, ry0: Float, rx1: Float, ry1: Float, sens: Int,
               ref: FloatArray? = null): CircleResult {
        val x0 = max(0, floor(min(rx0, rx1)).toInt()); val y0 = max(0, floor(min(ry0, ry1)).toInt())
        val x1 = min(img.w, ceil(max(rx0, rx1)).toInt()); val y1 = min(img.h, ceil(max(ry0, ry1)).toInt())
        val cw = x1 - x0; val ch = y1 - y0
        if (cw < 20 || ch < 20) return CircleResult(emptyList(), 0f)
        val n = cw * ch
        val rr = IntArray(n); val gg = IntArray(n); val bb = IntArray(n)
        val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256)
        for (y in 0 until ch) for (x in 0 until cw) {
            val c = img.px[(y + y0) * img.w + x + x0]
            val i = y * cw + x
            rr[i] = (c shr 16) and 255; gg[i] = (c shr 8) and 255; bb[i] = c and 255
            hr[rr[i]]++; hg[gg[i]]++; hb[bb[i]]++
        }
        fun median(h: IntArray): Int { var s = 0; for (v in 0 until 256) { s += h[v]; if (s >= n / 2) return v }; return 128 }
        val mr = median(hr); val mg = median(hg); val mb = median(hb)

        // färgavstånd till underlaget, utjämnat lite för att minska brus
        val dist = FloatArray(n)
        for (i in 0 until n) {
            val dr = (rr[i] - mr).toFloat(); val dg = (gg[i] - mg).toFloat(); val db = (bb[i] - mb).toFloat()
            dist[i] = sqrt(dr * dr + dg * dg + db * db)
        }
        val sm = boxBlur(dist, cw, ch, 1)

        // Otsu-tröskel på avstånden
        val bins = 256; val maxD = 442f
        val hist = IntArray(bins)
        for (v in sm) hist[((v / maxD) * (bins - 1)).toInt().coerceIn(0, bins - 1)]++
        var sumAll = 0.0; for (i in 0 until bins) sumAll += i.toDouble() * hist[i]
        var wB = 0.0; var sumB = 0.0; var best = 0.0; var thrBin = 20
        for (t in 0 until bins) {
            wB += hist[t]; if (wB == 0.0) continue
            val wF = n - wB; if (wF == 0.0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thrBin = t }
        }
        val factor = 1.3f - sens.coerceIn(1, 10) * 0.06f
        val thr = max(18f, thrBin * maxD / (bins - 1) * factor)

        var mask = ByteArray(n)
        for (i in 0 until n) if (sm[i] > thr) mask[i] = 1
        mask = dilate(erode(mask, cw, ch), cw, ch)   // öppning: bort med prickar
        mask = erode(dilate(mask, cw, ch), cw, ch)   // stängning: laga glansiga hål i metall

        // komponenter
        val lab = IntArray(n); val stack = IntArray(n)
        val areas = ArrayList<Int>()
        for (i in 0 until n) {
            if (mask[i].toInt() == 0 || lab[i] != 0) continue
            val id = areas.size + 1
            var sp = 0; var a = 0
            stack[sp++] = i; lab[i] = id
            while (sp > 0) {
                val j = stack[--sp]; a++
                val x = j % cw; val y = j / cw
                if (x > 0 && mask[j - 1].toInt() == 1 && lab[j - 1] == 0) { lab[j - 1] = id; stack[sp++] = j - 1 }
                if (x < cw - 1 && mask[j + 1].toInt() == 1 && lab[j + 1] == 0) { lab[j + 1] = id; stack[sp++] = j + 1 }
                if (y > 0 && mask[j - cw].toInt() == 1 && lab[j - cw] == 0) { lab[j - cw] = id; stack[sp++] = j - cw }
                if (y < ch - 1 && mask[j + cw].toInt() == 1 && lab[j + cw] == 0) { lab[j + cw] = id; stack[sp++] = j + cw }
            }
            areas.add(a)
        }
        val minA = max(12, n / 20000)
        val sorted = areas.filter { it >= minA }.sorted()
        if (sorted.isEmpty()) return CircleResult(emptyList(), 0f)
        // typisk yta för ETT föremål: lägre percentil (klumpar drar annars upp den)
        val p75 = sorted[(sorted.size * 0.75).toInt().coerceAtMost(sorted.size - 1)]
        val cand = sorted.filter { it >= p75 * 0.15f }
        // Typisk yta för ETT föremål. Ligger nästan allt i klungor finns få ensamma, så vi tar
        // den minsta "riktiga" klumpen (minst halva medianen) i stället för en mittenklump.
        val medA = cand[cand.size / 2].toFloat()
        var single = (cand.firstOrNull { it >= medA * 0.4f } ?: cand[cand.size / 2]).toFloat().coerceAtLeast(minA.toFloat())
        if (ref != null) {
            // räkna förgrundspixlar i användarens ruta
            val qx0 = (min(ref[0], ref[2]) - x0).toInt().coerceIn(0, cw); val qx1 = (max(ref[0], ref[2]) - x0).toInt().coerceIn(0, cw)
            val qy0 = (min(ref[1], ref[3]) - y0).toInt().coerceIn(0, ch); val qy1 = (max(ref[1], ref[3]) - y0).toInt().coerceIn(0, ch)
            var a = 0
            for (y in qy0 until qy1) for (x in qx0 until qx1) if (mask[y * cw + x].toInt() == 1) a++
            if (a >= minA) single = a.toFloat()
        }

        // samla pixlar per komponent
        val count = IntArray(areas.size + 1)
        for (i in 0 until n) if (lab[i] != 0) count[lab[i]]++
        val start = IntArray(areas.size + 2)
        for (k in 1..areas.size) start[k + 1] = start[k] + count[k]
        val fillPos = start.copyOf()
        val pix = IntArray(start[areas.size + 1])
        for (i in 0 until n) { val l = lab[i]; if (l != 0) pix[fillPos[l]++] = i }

        val out = ArrayList<Pt>()
        for (k in 1..areas.size) {
            val a = areas[k - 1]
            if (a < single * 0.3f || a < minA) continue
            val items = Math.round(a / single).coerceAtLeast(1)
            if (items > 40) continue // troligen kant/skugga – lägg till själv vid behov
            val s = start[k]; val e = start[k + 1]
            if (items == 1) {
                var sx = 0.0; var sy = 0.0
                for (q in s until e) { sx += pix[q] % cw; sy += pix[q] / cw }
                out.add(Pt((sx / a).toFloat() + x0, (sy / a).toFloat() + y0))
            } else {
                kmeans(pix, s, e, cw, items).forEach { out.add(Pt(it.x + x0, it.y + y0)) }
            }
        }
        return CircleResult(out, (2f * sqrt(single / PI.toFloat())).coerceAtLeast(8f), single)
    }

    private fun kmeans(pix: IntArray, s: Int, e: Int, cw: Int, k: Int): List<Pt> {
        val m = e - s
        val step = max(1, m / 3000)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        var q = s
        while (q < e) { xs.add((pix[q] % cw).toFloat()); ys.add((pix[q] / cw).toFloat()); q += step }
        val cnt = xs.size
        // start: punkter jämnt utspridda längs huvudaxeln
        val mx = xs.average().toFloat(); val my = ys.average().toFloat()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until cnt) { val dx = xs[i] - mx; val dy = ys[i] - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val ang = 0.5 * atan2(2 * sxy, sxx - syy)
        val ax = cos(ang).toFloat(); val ay = sin(ang).toFloat()
        val proj = (0 until cnt).map { (xs[it] - mx) * ax + (ys[it] - my) * ay }.sorted()
        val cx = FloatArray(k); val cy = FloatArray(k)
        for (j in 0 until k) {
            val t = proj[((j + 0.5f) / k * cnt).toInt().coerceIn(0, cnt - 1)]
            cx[j] = mx + ax * t; cy[j] = my + ay * t
        }
        val assign = IntArray(cnt)
        repeat(12) {
            for (i in 0 until cnt) {
                var bj = 0; var bd = Float.MAX_VALUE
                for (j in 0 until k) { val d = (xs[i] - cx[j]) * (xs[i] - cx[j]) + (ys[i] - cy[j]) * (ys[i] - cy[j]); if (d < bd) { bd = d; bj = j } }
                assign[i] = bj
            }
            val sx = FloatArray(k); val sy = FloatArray(k); val c = IntArray(k)
            for (i in 0 until cnt) { val j = assign[i]; sx[j] += xs[i]; sy[j] += ys[i]; c[j]++ }
            for (j in 0 until k) if (c[j] > 0) { cx[j] = sx[j] / c[j]; cy[j] = sy[j] / c[j] }
        }
        return (0 until k).map { Pt(cx[it], cy[it]) }
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (dy in -r..r) { val yy = y + dy; if (yy < 0 || yy >= h) continue
                for (dx in -r..r) { val xx = x + dx; if (xx < 0 || xx >= w) continue; s += a[yy * w + xx]; c++ } }
            out[y * w + x] = s / c
        }
        return out
    }

    private fun erode(m: ByteArray, w: Int, h: Int): ByteArray {
        val o = ByteArray(m.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (m[i].toInt() == 1 && m[i - 1].toInt() == 1 && m[i + 1].toInt() == 1 && m[i - w].toInt() == 1 && m[i + w].toInt() == 1) o[i] = 1
        }
        return o
    }

    private fun dilate(m: ByteArray, w: Int, h: Int): ByteArray {
        val o = ByteArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (m[i].toInt() == 1 || (x > 0 && m[i - 1].toInt() == 1) || (x < w - 1 && m[i + 1].toInt() == 1) ||
                (y > 0 && m[i - w].toInt() == 1) || (y < h - 1 && m[i + w].toInt() == 1)) o[i] = 1
        }
        return o
    }
}
