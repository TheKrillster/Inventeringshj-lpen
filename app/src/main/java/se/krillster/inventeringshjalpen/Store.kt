package se.krillster.inventeringshjalpen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Method(val label: String, val short: String) {
    HOLLOW("Runda ändar – ihåliga (rör, hylsor)", "Ihåliga ändar"),
    SOLID("Runda ändar – massiva (stång, stock, rullar)", "Massiva ändar"),
    LOOSE("Lösa småsaker i låda/på bord (skruv, mutter, O-ring)", "Lösa småsaker"),
    SHEETS("Skivor/lager i stapel (sidovy)", "Skivor i stapel"),
    MANUAL("Manuellt – tryck på varje sak", "Manuellt");
}

data class Material(
    val id: String,
    val name: String,
    val method: Method,
    val sens: Int,
    val perMark: Int,
    val article: String,
    val description: String,
)

data class HistoryEntry(
    val time: String,
    val material: String,
    val article: String,
    val batch: String,
    val description: String,
    val location: String,
    val comment: String,
    val marks: Int,
    val perMark: Int,
    val total: Int,
    val image: String,
)

object Store {
    private const val PREFS = "inventeringshjalpen"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun defaults() = listOf(
        Material("papperror", "Papprör", Method.HOLLOW, 5, 1, "", ""),
        Material("wellpapp", "Wellpappskivor", Method.SHEETS, 5, 1, "", ""),
        Material("skruvar", "Skruvar", Method.LOOSE, 5, 1, "", ""),
        Material("oringar", "O-ringar", Method.LOOSE, 5, 1, "", ""),
        Material("manuellt", "Övrigt (manuellt)", Method.MANUAL, 5, 1, "", ""),
    )

    fun materials(ctx: Context): List<Material> {
        val s = prefs(ctx).getString("materials", null) ?: return defaults()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Material(
                    o.getString("id"), o.getString("name"),
                    runCatching { Method.valueOf(o.getString("method")) }.getOrDefault(Method.MANUAL),
                    o.optInt("sens", 5), o.optInt("perMark", 1).coerceAtLeast(1),
                    o.optString("article", ""), o.optString("description", ""),
                )
            }.ifEmpty { defaults() }
        } catch (e: Exception) { defaults() }
    }

    fun saveMaterials(ctx: Context, list: List<Material>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("method", it.method.name)
                .put("sens", it.sens).put("perMark", it.perMark).put("article", it.article)
                .put("description", it.description))
        }
        prefs(ctx).edit().putString("materials", a.toString()).apply()
    }

    fun upsertMaterial(ctx: Context, m: Material) {
        val list = materials(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == m.id }
        if (i >= 0) list[i] = m else list.add(m)
        saveMaterials(ctx, list)
    }

    fun deleteMaterial(ctx: Context, id: String) = saveMaterials(ctx, materials(ctx).filter { it.id != id })

    fun newId() = UUID.randomUUID().toString().take(8)

    fun getLastMaterial(ctx: Context): String? = prefs(ctx).getString("lastMaterial", null)
    fun setLastMaterial(ctx: Context, id: String) = prefs(ctx).edit().putString("lastMaterial", id).apply()

    fun getField(ctx: Context, key: String) = prefs(ctx).getString("f_$key", "") ?: ""
    fun setField(ctx: Context, key: String, v: String) = prefs(ctx).edit().putString("f_$key", v).apply()

    fun history(ctx: Context): List<HistoryEntry> {
        val s = prefs(ctx).getString("history", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                HistoryEntry(
                    o.optString("time"), o.optString("material"), o.optString("article"), o.optString("batch"),
                    o.optString("description"), o.optString("location"), o.optString("comment"),
                    o.optInt("marks"), o.optInt("perMark", 1), o.optInt("total"), o.optString("image"),
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun addHistory(ctx: Context, h: HistoryEntry) {
        val list = listOf(h) + history(ctx)
        val a = JSONArray()
        list.take(500).forEach {
            a.put(JSONObject().put("time", it.time).put("material", it.material).put("article", it.article)
                .put("batch", it.batch).put("description", it.description).put("location", it.location)
                .put("comment", it.comment).put("marks", it.marks).put("perMark", it.perMark)
                .put("total", it.total).put("image", it.image))
        }
        prefs(ctx).edit().putString("history", a.toString()).apply()
    }

    fun clearHistory(ctx: Context) = prefs(ctx).edit().remove("history").apply()
}
