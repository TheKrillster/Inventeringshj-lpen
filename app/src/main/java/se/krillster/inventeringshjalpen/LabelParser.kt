package se.krillster.inventeringshjalpen

/** Plockar ut artikelnummer, batch, benämning och lagerplats ur OCR-text från en etikett. */
data class LabelInfo(
    val article: String? = null,
    val batch: String? = null,
    val description: String? = null,
    val location: String? = null,
    val expected: Int? = null,
) {
    fun isEmpty() = article == null && batch == null && description == null && location == null && expected == null
}

object LabelParser {
    private val batchRe = Regex("""(?i)\b(?:batch|lot|parti|charge|tillv\.?\s*order|tillverkningsorder)\s*(?:n[or]\.?|nr\.?|#)?\s*[:.\-]?\s*([A-Z0-9][A-Z0-9\-/]{2,})""")
    private val articleKeyRe = Regex("""(?i)\b(?:art(?:ikel)?\.?\s*(?:nr|no|nummer)?|item|sku)\s*[:.\-]?\s*([A-Z0-9][A-Z0-9\-./]{2,})""")
    private val articleRe = Regex("""\b(\d[0-9OIlSB]{1,2}-[0-9OIlSB]{3,5})\b""")
    private val locationRe = Regex("""\b([A-Z]{1,2}\d{3,4}[A-Z]?)\b""")
    private val descRe = Regex("""(?i)\b(?:[A-Z]{1,3}\s?\d{1,4}(?:[,.]\d+)?\s*(?:x\s*)?\d{2,5}\s*mm|\d{2,5}\s*[x×]\s*\d{2,5}(?:\s*[x×]\s*\d{1,5})?\s*mm?|D\s?\d{1,4}\b.*)""")

    fun parse(text: String): LabelInfo {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val joined = lines.joinToString("\n")

        var batch = batchRe.find(joined)?.groupValues?.get(1)
        if (batch == null) {
            // "BATCH" på en rad och numret på nästa
            val i = lines.indexOfFirst { it.contains("batch", ignoreCase = true) || it.contains("tillv", ignoreCase = true) }
            if (i >= 0 && i + 1 < lines.size) batch = Regex("""[A-Z0-9\-]{3,}""").find(lines[i + 1])?.value
        }
        batch = batch?.let { fixDigits(it) }

        // Lagrets eget format (t.ex. 53-0120) går före leverantörens artikelnummer
        var article = articleRe.find(joined)?.groupValues?.get(1)
        if (article == null) article = articleKeyRe.find(joined)?.groupValues?.get(1)
        article = article?.let { fixDigits(it) }

        // Måttrader som "Ø12,7 x 2,0 x 944,0 svarta" – hela raden är benämningen
        val dimRe = Regex("""(?i)^[Øø⌀]\s*\d|\d+(?:[,.]\d+)?\s*[x×]\s*\d+(?:[,.]\d+)?\s*[x×]\s*\d+""")
        val dimLine = lines.firstOrNull { dimRe.containsMatchIn(it) && !it.contains("batch", true) }
        val desc = dimLine ?: lines.firstOrNull { l ->
            !l.contains("batch", true) && descRe.containsMatchIn(l)
        }?.let { descRe.find(it)?.value?.trim() }

        val location = lines.asSequence()
            .filter { !it.contains("batch", true) }
            .mapNotNull { locationRe.find(it)?.groupValues?.get(1) }
            .firstOrNull { it != article && (desc == null || !desc.contains(it)) }

        return LabelInfo(article, batch, desc, location, expected(lines))
    }

    private val qtyKey = Regex("""(?i)st(ü|ue|u)ckzahl|antal|quantity|qty|menge|pcs|stk|anzahl""")
    private val numRe = Regex("""(?<![\d.,])\d{1,6}(?![\d.,])""")

    /** Förväntat antal, t.ex. "Stueckzahl 300" eller "Antal/emballage 886 864" (sista = rättat värde). */
    private fun expected(lines: List<String>): Int? {
        for ((i, l) in lines.withIndex()) {
            val km = qtyKey.find(l) ?: continue
            val after = numRe.findAll(l.substring(km.range.last + 1)).map { it.value.toInt() }.toList()
            if (after.isNotEmpty()) return after.last()
            if (i + 1 >= lines.size) continue
            val nums = numRe.findAll(lines[i + 1]).map { it.value }.toList()
            if (nums.isEmpty()) continue
            val words = l.trim().split(Regex("""\s+"""))
            val idx = words.indexOfFirst { qtyKey.containsMatchIn(it) }
            return when {
                words.size > 1 && nums.size == words.size && idx >= 0 -> nums[idx].toInt()
                words.size == 1 -> nums.last().toInt()
                else -> nums.firstOrNull { it.length >= 2 }?.toInt()
            }
        }
        return null
    }

    /** Vanliga OCR-förväxlingar i rena sifferfält. */
    private fun fixDigits(s: String): String {
        val digits = s.count { it.isDigit() }
        if (digits < s.count { it.isLetter() }) return s
        return s.map {
            when (it) {
                'O', 'o', 'Q', 'D' -> '0'
                'I', 'l', '|' -> '1'
                'S' -> '5'
                'B' -> '8'
                'Z' -> '2'
                else -> it
            }
        }.joinToString("")
    }
}
