package pkmn.tools

import java.io.File

/** Minimal RFC-4180 CSV reader (quoted fields, embedded commas/newlines). */
object Csv {
    fun read(file: File): List<Map<String, String>> {
        val rows = parse(file.readText())
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1).filter { it.size > 1 || (it.size == 1 && it[0].isNotEmpty()) }.map { r ->
            header.withIndex().associate { (i, h) -> h to (r.getOrNull(i) ?: "") }
        }
    }

    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = ArrayList() }
                '\r' -> {}
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
