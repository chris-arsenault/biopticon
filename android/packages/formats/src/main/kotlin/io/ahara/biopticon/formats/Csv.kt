package io.ahara.biopticon.formats

data class CsvShape(val delimiter: Char, val headers: List<String>, val rowCount: Int, val widths: Set<Int>)

object Csv {
    fun inspect(text: String): CsvShape {
        require(text.length <= 2_000_000) { "FILE_TOO_LARGE" }
        val source = text.removePrefix("\uFEFF")
        val delimiter = listOf(',', ';', '\t').maxBy { candidate ->
            parse(source, candidate).firstOrNull()?.size ?: 0
        }
        val rows = parse(source, delimiter)
        return CsvShape(delimiter, rows.firstOrNull().orEmpty(), (rows.size - 1).coerceAtLeast(0), rows.map { it.size }.toSet())
    }

    fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var index = 0
        fun endCell() { row.add(cell.toString()); cell.clear() }
        fun endRow() { endCell(); rows.add(row); row = mutableListOf() }
        while (index < text.length) {
            val c = text[index]
            when {
                c == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> { cell.append('"'); index++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> endCell()
                !quoted && (c == '\n' || c == '\r') -> { endRow(); if (c == '\r' && text.getOrNull(index + 1) == '\n') index++ }
                else -> cell.append(c)
            }
            require(cell.length <= 65536 && rows.size <= 100000) { "CSV_LIMIT" }
            index++
        }
        require(!quoted) { "UNCLOSED_QUOTE" }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
