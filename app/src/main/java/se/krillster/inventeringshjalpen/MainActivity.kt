package se.krillster.inventeringshjalpen

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), CountView.Listener {

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_PICK = 2
        private const val REQ_LABEL = 3
        private const val MAX_DIM = 2000

        private const val INK = 0xFF1D1D1B.toInt()
        private const val ACCENT = 0xFFE8541C.toInt()
        private const val BLUE = 0xFF1F5FBF.toInt()
        private const val MUTED = 0xFF6B6A66.toInt()
        private const val LINE = 0xFFDEDCD5.toInt()
        private const val BG = 0xFFF4F3EF.toInt()
    }

    private val ui = Handler(Looper.getMainLooper())

    private var material: Material? = null
    private var bitmap: Bitmap? = null
    private var gray: GrayImage? = null
    private var grayInv: GrayImage? = null
    private var color: ColorImage? = null
    private var sens = 5
    private var onCountScreen = false
    private var busy = false

    // originalfotot (för skivräkning i full upplösning)
    private var sourceUri: Uri? = null
    private var rawW = 0
    private var rawH = 0
    private var rotDeg = 0
    private var sheetPitchStrip = 0f    // senaste skivavstånd i remsans pixlar
    private var sheetHint: Float? = null
    private var pitchRow: View? = null
    private var refRow: View? = null
    private var looseRef: FloatArray? = null     // användarens ruta runt EN sak

    // delräkningar (t.ex. en bunt i taget) som summeras
    private val parts = ArrayList<Int>()
    private var addingPart = false
    private fun total() = parts.sum() + stats()[0]

    // fält i resultatet
    private val fieldKeys = listOf("article", "batch", "description", "location", "expected", "comment")
    private val fields = HashMap<String, String>()
    private var fieldViews = HashMap<String, EditText>()

    private lateinit var countView: CountView
    private var countText: TextView? = null
    private var hintText: TextView? = null
    private var materialButton: Button? = null
    private var toolButton: Button? = null
    private var editButton: Button? = null
    private var undoButton: Button? = null
    private var doneButton: Button? = null
    private var sensRow: View? = null
    private var progress: View? = null

    private val dp get() = resources.displayMetrics.density
    private fun Int.dp() = (this * dp).roundToInt()

    // ================= livscykel =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fieldKeys.forEach { fields[it] = Store.getField(this, it) }
        val mats = Store.materials(this)
        material = mats.firstOrNull { it.id == Store.getLastMaterial(this) } ?: mats.first()
        // Har Android stängt appen medan kameran var öppen? Fortsätt där vi var.
        if (savedInstanceState?.getBoolean("restore") == true && restoreSession()) return
        showHome()
        handleShareIntent(intent)
    }

    // ================= sparad session (om Android stänger appen medan kameran är öppen) =================
    private var resultOpen = false
    private fun sessionDir() = File(filesDir, "session").apply { mkdirs() }

    private fun saveSession() {
        val bmp = bitmap ?: return
        if (!onCountScreen) return
        try {
            val dir = sessionDir()
            val img = File(dir, "image.jpg")
            if (!img.exists() || sessionImageDirty) {
                FileOutputStream(img).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                sessionImageDirty = false
            }
            val o = JSONObject()
            o.put("material", material?.id ?: "")
            val ma = JSONArray()
            countView.marks.forEach { ma.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble()).put(if (it.auto) 1 else 0)) }
            o.put("marks", ma)
            countView.area?.let { o.put("area", JSONArray().put(it.left.toDouble()).put(it.top.toDouble()).put(it.right.toDouble()).put(it.bottom.toDouble())) }
            countView.line?.let { (a, b) -> o.put("line", JSONArray().put(a.x.toDouble()).put(a.y.toDouble()).put(b.x.toDouble()).put(b.y.toDouble())) }
            o.put("markSize", countView.markSize.toDouble())
            o.put("autoCount", countView.autoCount)
            val pa = JSONArray(); parts.forEach { pa.put(it) }; o.put("parts", pa)
            o.put("addingPart", addingPart)
            o.put("resultOpen", resultOpen)
            o.put("sens", sens)
            o.put("uri", sourceUri?.toString() ?: "")
            o.put("rawW", rawW); o.put("rawH", rawH); o.put("rot", rotDeg)
            o.put("pitch", sheetPitchStrip.toDouble())
            looseRef?.let { r -> o.put("ref", JSONArray().put(r[0].toDouble()).put(r[1].toDouble()).put(r[2].toDouble()).put(r[3].toDouble())) }
            File(dir, "state.json").writeText(o.toString())
        } catch (e: Exception) { /* bäst-effort */ }
    }

    private fun clearSession() {
        try { sessionDir().listFiles()?.forEach { it.delete() } } catch (e: Exception) {}
        sessionImageDirty = true
    }

    private var sessionImageDirty = true

    /** Återställ räkningen efter att Android stängt appen. Returnerar true om det lyckades. */
    private fun restoreSession(): Boolean {
        return try {
            val dir = sessionDir()
            val stFile = File(dir, "state.json"); val img = File(dir, "image.jpg")
            if (!stFile.exists() || !img.exists()) return false
            val o = JSONObject(stFile.readText())
            val bmp = BitmapFactory.decodeFile(img.absolutePath)?.let {
                if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false)
            } ?: return false
            Store.materials(this).firstOrNull { it.id == o.optString("material") }?.let { material = it }
            val c = toColor(bmp)
            bitmap = bmp; color = c; gray = toGray(c); grayInv = null
            sessionImageDirty = false
            val u = o.optString("uri", "")
            sourceUri = if (u.isNotEmpty()) Uri.parse(u) else null
            rawW = o.optInt("rawW", 0); rawH = o.optInt("rawH", 0); rotDeg = o.optInt("rot", 0)
            parts.clear()
            o.optJSONArray("parts")?.let { a -> for (i in 0 until a.length()) parts.add(a.optInt(i)) }
            addingPart = o.optBoolean("addingPart", false)
            showCount()
            countView.reset()
            countView.bitmap = bmp
            applyMaterialToView()
            sens = o.optInt("sens", sens)
            (sensRow?.findViewWithTag<SeekBar>("sens"))?.progress = sens - 1
            sheetPitchStrip = o.optDouble("pitch", 0.0).toFloat()
            o.optJSONArray("ref")?.let { a -> looseRef = floatArrayOf(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat(), a.optDouble(3).toFloat()) }
            o.optJSONArray("area")?.let { a -> countView.area = RectF(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat(), a.optDouble(3).toFloat()) }
            o.optJSONArray("line")?.let { a -> countView.line = Pair(Pt(a.optDouble(0).toFloat(), a.optDouble(1).toFloat()), Pt(a.optDouble(2).toFloat(), a.optDouble(3).toFloat())) }
            countView.markSize = o.optDouble("markSize", 20.0).toFloat()
            o.optJSONArray("marks")?.let { a ->
                for (i in 0 until a.length()) {
                    val m = a.optJSONArray(i) ?: continue
                    countView.marks.add(Mark(m.optDouble(0).toFloat(), m.optDouble(1).toFloat(), m.optInt(2) == 1))
                }
            }
            countView.autoCount = o.optInt("autoCount", 0)
            setTool(CountView.Tool.EDIT)
            countView.invalidate()
            updateCount()
            if (o.optBoolean("resultOpen", false)) showResult()
            true
        } catch (e: Exception) { false }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (onCountScreen && bitmap != null) { saveSession(); outState.putBoolean("restore", true) }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
            loadImage(uri)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onCountScreen) showHome() else super.onBackPressed()
    }

    // ================= hjälpare för UI =================
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun rounded(color: Int, radius: Int = 12, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radius * dp
            if (stroke != null) setStroke(1.dp(), stroke)
        }

    private fun btn(label: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (primary) Color.WHITE else INK)
            background = rounded(if (primary) ACCENT else Color.WHITE, 12, if (primary) null else LINE)
            stateListAnimator = null
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            minHeight = 46.dp(); minimumHeight = 46.dp()
            setOnClickListener { onClick() }
        }

    private fun setOn(b: Button?, on: Boolean) {
        b ?: return
        b.background = rounded(if (on) INK else Color.WHITE, 12, if (on) null else LINE)
        b.setTextColor(if (on) Color.WHITE else INK)
    }

    private fun label(s: String, size: Float = 14f, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                   weight: Float = 0f, top: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top.dp(); rightMargin = right.dp() }

    // ================= startsidan =================
    private fun showHome() {
        onCountScreen = false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(INK)
            setPadding(20.dp(), 22.dp(), 20.dp(), 20.dp())
            addView(label("Inventeringshjälpen", 24f, Color.WHITE, true))
            addView(label("Fota – räkna – rätta – rapportera", 14f, 0xFFBBBBBB.toInt()))
        }
        // liten knapp i hörnet som kallar på gubben
        val jubba = JubbaView(this)
        val egg = TextView(this).apply {
            text = "👷"; textSize = 18f; gravity = Gravity.CENTER
            background = rounded(0xFF3A3936.toInt(), 18)
            contentDescription = "Jubba"
            setOnClickListener { jubba.play() }
        }
        val headerFrame = FrameLayout(this).apply { setBackgroundColor(INK) }
        headerFrame.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        headerFrame.addView(egg, FrameLayout.LayoutParams(36.dp(), 36.dp(), Gravity.TOP or Gravity.END).apply { topMargin = 10.dp(); rightMargin = 10.dp() })
        root.addView(headerFrame)

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp(), 16.dp(), 16.dp(), 24.dp()) }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(label("Vad ska du räkna?", 13f, MUTED, true))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, lp(top = 6))
        fun renderList() {
            list.removeAllViews()
            for (m in Store.materials(this)) {
                val sel = m.id == material?.id
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(if (sel) 0xFFFFF3EC.toInt() else Color.WHITE, 12, if (sel) ACCENT else LINE)
                    setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                    addView(label(m.name, 16f, INK, true))
                    val extra = buildString {
                        append(m.method.short)
                        if (m.perMark > 1) append(" · ${m.perMark} st per markering")
                        if (m.article.isNotBlank()) append(" · art ${m.article}")
                    }
                    addView(label(extra, 13f, MUTED))
                    setOnClickListener { material = m; Store.setLastMaterial(this@MainActivity, m.id); renderList() }
                    setOnLongClickListener { editMaterial(m) { renderList() }; true }
                }
                list.addView(card, lp(top = 8))
            }
        }
        renderList()
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(btn("+ Ny materialtyp") { editMaterial(null) { renderList() } }, lp(0, weight = 1f, right = 8))
        addRow.addView(btn("Ändra vald") { material?.let { m -> editMaterial(m) { renderList() } } }, lp(0, weight = 1f))
        col.addView(addRow, lp(top = 10))
        col.addView(label("Tips: håll inne på en materialtyp för att ändra eller ta bort den.", 12f, MUTED), lp(top = 6))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            setPadding(16.dp(), 12.dp(), 16.dp(), 16.dp())
        }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(btn("Ta foto", true) { takePhoto(REQ_CAMERA) }, lp(0, weight = 1f, right = 8))
        r1.addView(btn("Välj bild") { pickImage() }, lp(0, weight = 1f))
        actions.addView(r1)
        actions.addView(btn("Historik") { showHistory() }, lp(top = 8))
        root.addView(actions)
        val screen = FrameLayout(this)
        screen.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.addView(jubba, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(screen)
    }

    private fun editMaterial(existing: Material?, done: () -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 8.dp(), 20.dp(), 0) }
        fun field(hint: String, value: String, number: Boolean = false) = EditText(this).apply {
            setText(value); this.hint = hint; setSingleLine()
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
        }
        col.addView(label("Namn", 12f, MUTED, true))
        val name = field("t.ex. Kartongrör 50 mm", existing?.name ?: "")
        col.addView(name)
        col.addView(label("Hur ska det räknas?", 12f, MUTED, true), lp(top = 10))
        val group = RadioGroup(this)
        val ids = HashMap<Int, Method>()
        Method.values().forEach { m ->
            val rb = RadioButton(this).apply { text = m.label; id = View.generateViewId() }
            ids[rb.id] = m; group.addView(rb)
            if (m == (existing?.method ?: Method.HOLLOW)) rb.isChecked = true
        }
        col.addView(group)
        col.addView(label("Antal per markering (t.ex. 10 om varje markering är en bunt om 10)", 12f, MUTED, true), lp(top = 10))
        val per = field("1", (existing?.perMark ?: 1).toString(), true)
        col.addView(per)
        col.addView(label("Standard-artikelnummer (valfritt)", 12f, MUTED, true), lp(top = 10))
        val art = field("fylls i automatiskt", existing?.article ?: "")
        col.addView(art)
        col.addView(label("Standard-benämning (valfritt)", 12f, MUTED, true), lp(top = 10))
        val desc = field("t.ex. D32 1320mm", existing?.description ?: "")
        col.addView(desc)
        val sensLabel = label("Känslighet: ${existing?.sens ?: 5}", 12f, MUTED, true)
        col.addView(sensLabel, lp(top = 10))
        val sb = SeekBar(this).apply {
            max = 9; progress = (existing?.sens ?: 5) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { sensLabel.text = "Känslighet: ${p + 1}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(sb)
        val scroll = ScrollView(this).apply { addView(col) }

        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Ny materialtyp" else "Ändra materialtyp")
            .setView(scroll)
            .setPositiveButton("Spara") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { "Material" }
                val m = Material(
                    existing?.id ?: Store.newId(), n, ids[group.checkedRadioButtonId] ?: Method.HOLLOW,
                    sb.progress + 1, per.text.toString().toIntOrNull()?.coerceIn(1, 100000) ?: 1,
                    art.text.toString().trim(), desc.text.toString().trim(),
                )
                Store.upsertMaterial(this, m)
                material = m; Store.setLastMaterial(this, m.id)
                done()
            }
            .setNegativeButton("Avbryt", null)
        if (existing != null) b.setNeutralButton("Ta bort") { _, _ ->
            AlertDialog.Builder(this).setMessage("Ta bort \"${existing.name}\"?")
                .setPositiveButton("Ta bort") { _, _ ->
                    Store.deleteMaterial(this, existing.id)
                    if (material?.id == existing.id) material = Store.materials(this).first()
                    done()
                }.setNegativeButton("Avbryt", null).show()
        }
        b.show()
    }

    // ================= bild in =================
    private fun photoFile(req: Int) = File(cacheDir, if (req == REQ_LABEL) "label.jpg" else "photo.jpg")

    private fun takePhoto(req: Int) {
        saveSession()   // ifall Android stänger appen medan kameran är öppen
        val f = photoFile(req)
        f.delete()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uri)
        }
        try {
            @Suppress("DEPRECATION") startActivityForResult(i, req)
        } catch (e: ActivityNotFoundException) {
            toast("Hittar ingen kameraapp")
        }
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        try {
            @Suppress("DEPRECATION") startActivityForResult(Intent.createChooser(i, "Välj bild"), REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            toast("Hittar ingen bildväljare")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAMERA && addingPart) { // avbröt nästa del
                addingPart = false
                if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                updateCount()
            }
            return
        }
        when (requestCode) {
            REQ_CAMERA -> loadImage(Uri.fromFile(photoFile(REQ_CAMERA)))
            REQ_PICK -> data?.data?.let { loadImage(it) }
            REQ_LABEL -> readLabelPhoto(Uri.fromFile(photoFile(REQ_LABEL)))
        }
    }

    private fun decode(uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        val s = min(1f, maxDim.toFloat() / max(bmp.width, bmp.height))
        if (rot != 0f || s < 1f) {
            val m = Matrix().apply { postScale(s, s); postRotate(rot) }
            val nb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (nb != bmp) bmp.recycle()
            bmp = nb
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun readMeta(uri: Uri): Triple<Int, Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val rot = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) { 0 }
        return Triple(bounds.outWidth, bounds.outHeight, rot)
    }

    /** Remsa runt linjen ur originalfotot i full upplösning. */
    private class Strip(val img: GrayImage, val left: Float, val top: Float, val sample: Int, val workScale: Float)

    @Suppress("DEPRECATION")
    private fun sheetStrip(p0: Pt, p1: Pt): Strip? {
        val uri = sourceUri ?: return null
        val bmp = bitmap ?: return null
        if (rawW <= 0 || rawH <= 0) return null
        val fullW = if (rotDeg == 90 || rotDeg == 270) rawH else rawW
        val fullH = if (rotDeg == 90 || rotDeg == 270) rawW else rawH
        val ws = bmp.width.toFloat() / fullW
        if (ws > 0.95f) return null // originalet är inte större än arbetsbilden
        val ax = p0.x / ws; val ay = p0.y / ws; val bx = p1.x / ws; val by = p1.y / ws
        val margin = max(90f, hypot(bx - ax, by - ay) / 25f)
        val l = max(0f, min(ax, bx) - margin); val t = max(0f, min(ay, by) - margin)
        val r = min(fullW.toFloat(), max(ax, bx) + margin); val b = min(fullH.toFloat(), max(ay, by) + margin)
        if (r - l < 10 || b - t < 10) return null
        var sample = 1
        while ((r - l) / sample * (b - t) / sample > 24_000_000f) sample *= 2
        fun toRaw(x: Float, y: Float): Pair<Float, Float> = when (rotDeg) {
            90 -> Pair(y, rawH - 1 - x)
            180 -> Pair(rawW - 1 - x, rawH - 1 - y)
            270 -> Pair(rawW - 1 - y, x)
            else -> Pair(x, y)
        }
        val c1 = toRaw(l, t); val c2 = toRaw(r, b)
        val rect = Rect(
            min(c1.first, c2.first).toInt().coerceIn(0, rawW - 1), min(c1.second, c2.second).toInt().coerceIn(0, rawH - 1),
            max(c1.first, c2.first).toInt().coerceIn(1, rawW), max(c1.second, c2.second).toInt().coerceIn(1, rawH))
        val region = contentResolver.openInputStream(uri)?.use { input ->
            val dec = BitmapRegionDecoder.newInstance(input, false) ?: return null
            try { dec.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) } finally { dec.recycle() }
        } ?: return null
        val rotated = if (rotDeg != 0) {
            val m = Matrix().apply { postRotate(rotDeg.toFloat()) }
            Bitmap.createBitmap(region, 0, 0, region.width, region.height, m, true).also { if (it != region) region.recycle() }
        } else region
        val g = toGray(toColor(rotated))
        rotated.recycle()
        return Strip(g, l, t, sample, ws)
    }

    private fun loadImage(uri: Uri) {
        if (!addingPart) parts.clear()
        addingPart = false
        showCount()
        setBusy(true, "Läser bild…")
        Thread {
            val bmp = try { decode(uri, MAX_DIM) } catch (e: Throwable) { null }
            val meta = try { readMeta(uri) } catch (e: Throwable) { null }
            val c = bmp?.let { toColor(it) }
            val g = c?.let { toGray(it) }
            ui.post {
                setBusy(false)
                if (bmp == null || g == null) { toast("Kunde inte läsa bilden"); showHome(); return@post }
                bitmap = bmp; gray = g; grayInv = null; color = c
                sessionImageDirty = true
                sourceUri = uri; rawW = meta?.first ?: 0; rawH = meta?.second ?: 0; rotDeg = meta?.third ?: 0
                sheetHint = null; sheetPitchStrip = 0f
                countView.reset()
                countView.bitmap = bmp
                applyMaterialToView()
                runOcr(bmp, auto = true)
            }
        }.start()
    }

    private fun toColor(b: Bitmap): ColorImage {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return ColorImage(b.width, b.height, px)
    }

    private fun toGray(ci: ColorImage): GrayImage {
        val w = ci.w; val h = ci.h
        val px = ci.px
        val g = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            g[i] = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
        }
        return GrayImage(w, h, g)
    }

    private fun inverted(): GrayImage? {
        val g = gray ?: return null
        grayInv?.let { return it }
        val inv = GrayImage(g.w, g.h, FloatArray(g.px.size) { 255f - g.px[it] })
        grayInv = inv
        return inv
    }

    // ================= räkneskärmen =================
    private fun showCount() {
        onCountScreen = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(INK); gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 8.dp(), 14.dp(), 8.dp())
        }
        val mb = Button(this).apply {
            isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
            background = rounded(0xFF3A3936.toInt(), 18); stateListAnimator = null
            setPadding(14.dp(), 6.dp(), 14.dp(), 6.dp()); minHeight = 38.dp(); minimumHeight = 38.dp()
            setOnClickListener { chooseMaterial() }
        }
        materialButton = mb
        top.addView(mb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val ct = label("–", 26f, Color.WHITE, true)
        countText = ct
        top.addView(ct)
        root.addView(top)

        val stage = FrameLayout(this)
        countView = CountView(this)
        countView.listener = this
        stage.addView(countView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val ht = label("", 13f, Color.WHITE).apply {
            background = rounded(0xB8000000.toInt(), 16); setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
        }
        hintText = ht
        stage.addView(ht, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 10.dp() })
        val pg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0x88000000.toInt())
            addView(ProgressBar(this@MainActivity))
            addView(label("Räknar…", 15f, Color.WHITE, true).apply { tag = "busyText" })
            visibility = View.GONE; isClickable = true
        }
        progress = pg
        stage.addView(pg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10.dp(), 8.dp(), 10.dp(), 10.dp()) }
        val sr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sr.addView(label("Känslighet", 13f, MUTED))
        val sb = SeekBar(this).apply {
            max = 9; progress = sens - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { sens = p + 1 }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            tag = "sens"
        }
        sr.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sr.addView(btn("Räkna om") { recount() })
        sensRow = sr
        bottom.addView(sr)
        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pr.addView(label("Räknar den fel avstånd?", 13f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pr.addView(btn("Tätare") { adjustPitch(0.5f) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, right = 6))
        pr.addView(btn("Glesare") { adjustPitch(2f) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        pitchRow = pr
        bottom.addView(pr, lp(top = 4))
        val rr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rr.addView(label("Ligger de i klungor? Visa hur stor EN är.", 13f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rr.addView(btn("Visa en sak") {
            if (countView.area == null) { toast("Dra först en ruta runt allt som ska räknas"); return@btn }
            setTool(CountView.Tool.REF)
        }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        refRow = rr
        bottom.addView(rr, lp(top = 4))
        val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tb = btn("Område") { setTool(if (material?.method == Method.SHEETS) CountView.Tool.LINE else CountView.Tool.AREA) }
        val eb = btn("Rätta") { setTool(CountView.Tool.EDIT) }
        val ub = btn("Ångra") { countView.undo() }
        val db = btn("Klar", true) { showResult() }
        toolButton = tb; editButton = eb; undoButton = ub; doneButton = db
        tr.addView(tb, lp(0, weight = 1f, right = 6))
        tr.addView(eb, lp(0, weight = 1f, right = 6))
        tr.addView(ub, lp(0, weight = 1f, right = 6))
        tr.addView(db, lp(0, weight = 1f))
        bottom.addView(tr, lp(top = 6))
        root.addView(bottom)
        setContentView(root)
        updateCount()
    }

    private fun setBusy(on: Boolean, msg: String = "Räknar…") {
        busy = on
        progress?.visibility = if (on) View.VISIBLE else View.GONE
        (progress?.findViewWithTag<TextView>("busyText"))?.text = msg
    }

    private fun applyMaterialToView() {
        val m = material ?: return
        sens = m.sens
        (sensRow?.findViewWithTag<SeekBar>("sens"))?.progress = sens - 1
        materialButton?.text = "${m.name}  ▾"
        countView.sheetMode = m.method == Method.SHEETS
        toolButton?.text = if (m.method == Method.SHEETS) "Linje" else "Område"
        toolButton?.visibility = if (m.method == Method.MANUAL) View.GONE else View.VISIBLE
        sensRow?.visibility = if (m.method == Method.MANUAL) View.GONE else View.VISIBLE
        pitchRow?.visibility = if (m.method == Method.SHEETS) View.VISIBLE else View.GONE
        refRow?.visibility = if (m.method == Method.LOOSE) View.VISIBLE else View.GONE
        looseRef = null
        if (m.method == Method.MANUAL) {
            countView.markSize = (bitmap?.let { max(it.width, it.height) } ?: 1000) / 40f
        }
        setTool(when (m.method) {
            Method.SHEETS -> CountView.Tool.LINE
            Method.MANUAL -> CountView.Tool.EDIT
            else -> CountView.Tool.AREA
        })
        updateCount()
    }

    private fun setTool(t: CountView.Tool) {
        countView.tool = t
        setOn(toolButton, t != CountView.Tool.EDIT)
        setOn(editButton, t == CountView.Tool.EDIT)
        hintText?.text = when (t) {
            CountView.Tool.REF -> "Dra en tät ruta runt EN sak – gärna mitt i bilden"
            CountView.Tool.AREA -> if (material?.method == Method.LOOSE)
                "Dra en ruta innanför lådans kanter"
            else "Dra en ruta runt det som ska räknas"
            CountView.Tool.LINE -> "Dra en linje tvärs över stapeln, från översta till understa skivan"
            CountView.Tool.EDIT -> if (material?.method == Method.SHEETS)
                "Tryck nära linjen: lägg till / ta bort · Nyp: zooma"
            else "Tryck: lägg till / ta bort · Dra: flytta · Nyp: zooma"
        }
    }

    private fun chooseMaterial() {
        val mats = Store.materials(this)
        val names = mats.map { "${it.name}  (${it.method.short})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Vad räknar du?")
            .setItems(names) { _, i ->
                val m = mats[i]
                if (m.id == material?.id) return@setItems
                material = m; Store.setLastMaterial(this, m.id)
                countView.reset()
                applyMaterialToView()
            }
            .setNeutralButton("Ny materialtyp") { _, _ ->
                editMaterial(null) { countView.reset(); applyMaterialToView() }
            }
            .show()
    }

    override fun onRef(r: RectF) {
        looseRef = floatArrayOf(r.left, r.top, r.right, r.bottom)
        val a = countView.area ?: return
        toast("Storlek inlärd – räknar om")
        onArea(a)
    }

    override fun onArea(r: RectF) {
        val m = material ?: return
        val g = (if (m.method == Method.SOLID) inverted() else gray) ?: return
        val ci = color ?: return
        if (busy) return
        setBusy(true)
        val s = sens
        Thread {
            val res = try {
                if (m.method == Method.LOOSE) LooseDetector.detect(ci, r.left, r.top, r.right, r.bottom, s, looseRef)
                else CircleDetector.detect(g, r.left, r.top, r.right, r.bottom, s)
            } catch (e: Throwable) { CircleResult(emptyList(), 0f) }
            ui.post {
                setBusy(false)
                if (res.diameter > 0) countView.markSize = res.diameter
                countView.setAuto(res.points)
                setTool(CountView.Tool.EDIT)
                toast(if (res.points.isEmpty()) "Hittade inget – prova högre känslighet eller tryck själv" else "${res.points.size} hittade – kontrollera och rätta")
            }
        }.start()
    }

    override fun onLine(p0: Pt, p1: Pt) = countSheets(p0, p1, null)

    private fun countSheets(p0: Pt, p1: Pt, hint: Float?) {
        val g = gray ?: return
        if (busy) return
        setBusy(true)
        val s = sens
        sheetHint = hint
        Thread {
            var res: SheetResult
            var pitchStrip: Float
            try {
                val strip = try { sheetStrip(p0, p1) } catch (e: Throwable) { null }
                if (strip != null) {
                    val k = 1f / strip.sample
                    val a = Pt((p0.x / strip.workScale - strip.left) * k, (p0.y / strip.workScale - strip.top) * k)
                    val b = Pt((p1.x / strip.workScale - strip.left) * k, (p1.y / strip.workScale - strip.top) * k)
                    val r = SheetDetector.detect(strip.img, a, b, s, hint)
                    pitchStrip = r.pitch
                    val back = { q: Pt -> Pt((q.x / k + strip.left) * strip.workScale, (q.y / k + strip.top) * strip.workScale) }
                    res = SheetResult(r.points.map(back), r.pitch / k * strip.workScale)
                } else {
                    res = SheetDetector.detect(g, p0, p1, s, hint)
                    pitchStrip = res.pitch
                }
            } catch (e: Throwable) { res = SheetResult(emptyList(), 0f); pitchStrip = 0f }
            ui.post {
                setBusy(false)
                sheetPitchStrip = pitchStrip
                if (pitchStrip in 0.5f..6.5f && res.points.size > 30) {
                    AlertDialog.Builder(this).setTitle("Skivorna är för tunna i bilden")
                        .setMessage("Varje skiva är bara ca ${pitchStrip.roundToInt()} pixlar i fotot, för lite för en säker räkning. " +
                            "Gå närmare och fota en bunt i taget – tryck sedan Klar → \"+ Lägg till del\" för nästa bunt, så summeras de.")
                        .setPositiveButton("OK", null).show()
                }
                if (res.pitch > 0) countView.markSize = res.pitch * 0.85f
                countView.setAuto(res.points)
                setTool(CountView.Tool.EDIT)
                toast(if (res.points.isEmpty()) "Hittade inga skivor – dra linjen tvärs över kanterna" else "${res.points.size} skivor – zooma in och kolla. Varannan? Tryck Tätare/Glesare")
            }
        }.start()
    }

    /** Tätare (factor 0.5) / Glesare (factor 2): användaren rättar skivavståndet. */
    private fun adjustPitch(factor: Float) {
        val l = countView.line ?: run { toast("Dra först en linje"); return }
        if (sheetPitchStrip <= 0f) { toast("Räkna först"); return }
        val h = sheetPitchStrip * factor
        if (h < 3f) { toast("Tätare än så går inte – fota närmare"); return }
        countSheets(l.first, l.second, h)
    }

    private fun recount() {
        val m = material ?: return
        if (m.method == Method.SHEETS) {
            val l = countView.line ?: run { toast("Dra först en linje"); return }
            countSheets(l.first, l.second, null)
        } else {
            val r = countView.area ?: run { toast("Dra först en ruta"); return }
            onArea(r)
        }
    }

    override fun onMarksChanged() = updateCount()

    private fun stats(): IntArray {
        val auto = countView.marks.count { it.auto }
        val manual = countView.marks.size - auto
        val removed = max(0, countView.autoCount - auto)
        val per = material?.perMark ?: 1
        return intArrayOf(countView.marks.size * per, auto, manual, removed, countView.marks.size)
    }

    private fun updateCount() {
        if (!onCountScreen) return
        val st = stats()
        countText?.text = if (bitmap == null) "–" else if (parts.isEmpty()) "${st[0]} st" else "${total()} st (del ${parts.size + 1})"
        undoButton?.isEnabled = countView.canUndo
        undoButton?.alpha = if (countView.canUndo) 1f else 0.4f
        doneButton?.isEnabled = st[4] > 0
        doneButton?.alpha = if (st[4] > 0) 1f else 0.4f
    }

    // ================= etikett (OCR) =================
    private fun runOcr(bmp: Bitmap, auto: Boolean) {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val info = LabelParser.parse(t.text)
                val m = material
                // nytt foto: töm fälten som hör till förra räkningen (men inte mitt i en räkning i delar)
                if (auto && parts.isEmpty()) {
                    fields["article"] = m?.article ?: ""
                    fields["description"] = m?.description ?: ""
                    fields["batch"] = ""
                    fields["comment"] = ""
                }
                info.article?.let { fields["article"] = it }
                info.batch?.let { fields["batch"] = it }
                info.description?.let { fields["description"] = it }
                info.location?.let { fields["location"] = it }
                if (auto && parts.isEmpty()) fields["expected"] = ""
                info.expected?.let { fields["expected"] = it.toString() }
                fieldKeys.forEach { k -> fieldViews[k]?.setText(fields[k] ?: "") }
                if (!info.isEmpty()) {
                    toast("Etikett läst: " + listOfNotNull(info.article, info.batch?.let { "batch $it" }).joinToString(" · ").ifEmpty { "delvis" })
                } else if (!auto) toast("Hittade ingen läsbar text – skriv in för hand")
            }
            .addOnFailureListener { if (!auto) toast("Textigenkänning misslyckades") }
            .addOnCompleteListener { rec.close() }
    }

    private fun readLabelPhoto(uri: Uri) {
        Thread {
            val b = try { decode(uri, 1600) } catch (e: Throwable) { null }
            ui.post { if (b != null) runOcr(b, auto = false) else toast("Kunde inte läsa etikettbilden") }
        }.start()
    }

    // ================= resultat =================
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("sv", "SE")).format(Date())

    private fun report(time: String): String {
        val st = stats()
        val m = material
        val per = m?.perMark ?: 1
        val sb = StringBuilder()
        sb.append("RÄKNING – ${m?.name ?: ""}\n")
        sb.append("Datum:       $time\n")
        sb.append("Artikelnr:   ${fields["article"].orEmpty().ifBlank { "–" }}\n")
        sb.append("Benämning:   ${fields["description"].orEmpty().ifBlank { "–" }}\n")
        sb.append("Batchnr:     ${fields["batch"].orEmpty().ifBlank { "–" }}\n")
        sb.append("Lagerplats:  ${fields["location"].orEmpty().ifBlank { "–" }}\n")
        val tot = total()
        sb.append("Antal:       $tot st")
        val exp = fields["expected"].orEmpty().trim().toIntOrNull()
        if (parts.isNotEmpty()) sb.append("  (delar: " + (parts + st[0]).joinToString(" + ") + ")")
        else if (per > 1) sb.append("  (${st[4]} markeringar × $per)")
        sb.append("\n")
        if (st[2] > 0 || st[3] > 0) sb.append("             (auto ${st[1]}, +${st[2]} tillagda, −${st[3]} borttagna)\n")
        if (exp != null) {
            val diff = tot - exp
            sb.append("Förväntat:   $exp st  →  " + (if (diff == 0) "stämmer" else if (diff > 0) "+$diff st" else "−${-diff} st") + "\n")
        }
        val c = fields["comment"].orEmpty()
        if (c.isNotBlank()) sb.append("Kommentar:   $c\n")
        return sb.toString().trimEnd()
    }

    private fun exportBitmap(time: String): Bitmap? {
        val st = stats()
        val h1 = listOf(fields["article"].orEmpty(), fields["description"].orEmpty()).filter { it.isNotBlank() }
            .joinToString("  ").ifBlank { material?.name ?: "" } +
            (fields["batch"].orEmpty().takeIf { it.isNotBlank() }?.let { "   Batch $it" } ?: "")
        val h2 = "${total()} st" + (if (parts.isNotEmpty()) " (del ${parts.size + 1}: ${st[0]})" else "") + " · ${material?.name ?: ""}" +
            (fields["location"].orEmpty().takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + " · $time"
        return countView.renderExport(h1, h2)
    }

    private fun showResult() {
        val time = now()
        val st = stats()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 12.dp(), 20.dp(), 4.dp()) }
        val sum = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFFFFF3EC.toInt(), 12, 0xFFF6CDB8.toInt())
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
            addView(label("${total()}", 40f, INK, true))
            val per = material?.perMark ?: 1
            val det = buildString {
                append("st ${material?.name ?: ""}\n")
                if (parts.isNotEmpty()) append("Delar: " + (parts + st[0]).joinToString(" + ") + "\n")
                if (per > 1) append("${st[4]} markeringar × $per\n")
                append("${st[1]} auto · ${st[2]} tillagda · ${st[3]} borttagna")
            }
            addView(label(det, 12f, MUTED).apply { setPadding(12.dp(), 0, 0, 0) })
        }
        col.addView(sum)
        val preview = label("", 12f, MUTED).apply { typeface = Typeface.MONOSPACE; setPadding(0, 10.dp(), 0, 0) }
        fieldViews = HashMap()
        val names = mapOf("article" to "Artikelnummer", "batch" to "Batchnummer", "description" to "Benämning",
            "expected" to "Förväntat antal (enligt etikett)",
            "location" to "Lagerplats", "comment" to "Kommentar")
        for (k in fieldKeys) {
            col.addView(label(names[k] ?: k, 12f, MUTED, true), lp(top = 10))
            val et = EditText(this).apply {
                setText(fields[k] ?: ""); setSingleLine()
                if (k == "batch") inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                if (k == "expected") inputType = InputType.TYPE_CLASS_NUMBER
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        fields[k] = s?.toString() ?: ""
                        Store.setField(this@MainActivity, k, fields[k] ?: "")
                        preview.text = report(time)
                    }
                })
            }
            fieldViews[k] = et
            col.addView(et)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Fota etikett") { takePhoto(REQ_LABEL) }, lp(0, weight = 1f, right = 8))
        row.addView(btn("Kopiera") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Räkning", report(time))); toast("Kopierat")
        }, lp(0, weight = 1f))
        col.addView(row, lp(top = 12))
        var dlgRef: AlertDialog? = null
        col.addView(btn("+ Lägg till del (fota nästa bunt)") {
            parts.add(stats()[0]); addingPart = true
            dlgRef?.dismiss()
            takePhoto(REQ_CAMERA)
        }, lp(top = 8))
        col.addView(label("Stor stapel? Fota en bunt i taget på nära håll – delarna summeras.", 12f, MUTED), lp(top = 4))
        preview.text = report(time)
        col.addView(preview)
        val scroll = ScrollView(this).apply { addView(col) }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Resultat")
            .setView(scroll)
            .setPositiveButton("Spara", null)
            .setNeutralButton("Dela", null)
            .setNegativeButton("Tillbaka", null)
            .create()
        dlgRef = dlg
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (save(time)) { toast("Sparat i historiken"); dlg.dismiss() }
            }
            dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { share(time) }
        }
        resultOpen = true
        dlg.setOnDismissListener { fieldViews = HashMap(); resultOpen = false }
        dlg.show()
    }

    private fun save(time: String): Boolean {
        val st = stats()
        val bmp = exportBitmap(time)
        var path = ""
        if (bmp != null) {
            val dir = File(filesDir, "rakningar").apply { mkdirs() }
            val f = File(dir, "rakning_${System.currentTimeMillis()}.jpg")
            try { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }; path = f.absolutePath }
            catch (e: Exception) { toast("Kunde inte spara bilden") }
        }
        Store.addHistory(this, HistoryEntry(
            time, material?.name ?: "", fields["article"].orEmpty(), fields["batch"].orEmpty(),
            fields["description"].orEmpty(), fields["location"].orEmpty(), fields["comment"].orEmpty(),
            st[4] + parts.size, material?.perMark ?: 1, total(), path,
        ))
        parts.clear()
        updateCount()
        clearSession()
        return true
    }

    private fun share(time: String) {
        val bmp = exportBitmap(time)
        val i = Intent(Intent.ACTION_SEND)
        i.putExtra(Intent.EXTRA_TEXT, report(time))
        i.putExtra(Intent.EXTRA_SUBJECT, "Räkning ${fields["article"].orEmpty()} ${fields["batch"].orEmpty()}".trim())
        if (bmp != null) {
            val f = File(cacheDir, "rakning.jpg")
            try {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                i.type = "image/jpeg"
                i.putExtra(Intent.EXTRA_STREAM, uri)
                i.clipData = ClipData.newRawUri("", uri)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { i.type = "text/plain" }
        } else i.type = "text/plain"
        try { startActivity(Intent.createChooser(i, "Dela räkning")) } catch (e: ActivityNotFoundException) { toast("Ingen app att dela till") }
    }

    // ================= historik =================
    private fun showHistory() {
        val list = Store.history(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(), 8.dp(), 20.dp(), 8.dp()) }
        if (list.isEmpty()) col.addView(label("Inga sparade räkningar ännu.", 14f, MUTED))
        for (h in list) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, 10.dp(), 0, 10.dp())
                addView(label("${h.total} st · ${h.material}", 16f, INK, true))
                addView(label(listOf(h.time, h.article.ifBlank { null }?.let { "art $it" }, h.batch.ifBlank { null }?.let { "batch $it" },
                    h.location.ifBlank { null }).filterNotNull().joinToString(" · "), 13f, MUTED))
                setOnClickListener { shareHistory(h) }
            }
            col.addView(item)
            col.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        if (list.isNotEmpty()) col.addView(label("Tryck på en rad för att dela den.", 12f, MUTED), lp(top = 8))
        val b = AlertDialog.Builder(this).setTitle("Historik").setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton("Stäng", null)
        if (list.isNotEmpty()) {
            b.setPositiveButton("Exportera CSV") { _, _ -> exportCsv(list) }
            b.setNeutralButton("Rensa") { _, _ ->
                AlertDialog.Builder(this).setMessage("Rensa hela historiken?")
                    .setPositiveButton("Rensa") { _, _ ->
                        list.forEach { if (it.image.isNotBlank()) File(it.image).delete() }
                        Store.clearHistory(this); toast("Historiken rensad")
                    }.setNegativeButton("Avbryt", null).show()
            }
        }
        b.show()
    }

    private fun shareHistory(h: HistoryEntry) {
        val txt = buildString {
            append("RÄKNING – ${h.material}\nDatum:       ${h.time}\n")
            append("Artikelnr:   ${h.article.ifBlank { "–" }}\nBenämning:   ${h.description.ifBlank { "–" }}\n")
            append("Batchnr:     ${h.batch.ifBlank { "–" }}\nLagerplats:  ${h.location.ifBlank { "–" }}\n")
            append("Antal:       ${h.total} st")
            if (h.perMark > 1) append("  (${h.marks} markeringar × ${h.perMark})")
            if (h.comment.isNotBlank()) append("\nKommentar:   ${h.comment}")
        }
        val i = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, txt); type = "text/plain" }
        val f = File(h.image)
        if (h.image.isNotBlank() && f.exists()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            i.type = "image/jpeg"; i.putExtra(Intent.EXTRA_STREAM, uri)
            i.clipData = ClipData.newRawUri("", uri); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(i, "Dela räkning")) } catch (e: ActivityNotFoundException) { toast("Ingen app att dela till") }
    }

    private fun exportCsv(list: List<HistoryEntry>) {
        fun q(s: String) = if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder("﻿Datum;Material;Artikelnr;Benämning;Batch;Lagerplats;Markeringar;Per markering;Antal;Kommentar\n")
        for (h in list) {
            sb.append(listOf(h.time, h.material, h.article, h.description, h.batch, h.location,
                h.marks.toString(), h.perMark.toString(), h.total.toString(), h.comment).joinToString(";") { q(it) }).append("\n")
        }
        val f = File(cacheDir, "rakningar.csv")
        f.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(i, "Exportera historik")) } catch (e: ActivityNotFoundException) { toast("Ingen app att dela till") }
    }
}
