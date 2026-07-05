package org.takopi.percept.perception.audio

/**
 * Parser for the AudioSet class map CSV (`index,mid,display_name`). Display
 * names can be quoted and contain commas ("Boat, Water vehicle"), so only the
 * first two commas delimit fields.
 */
object YamnetClassMap {
    fun parse(lines: Sequence<String>): List<String> {
        val labels = mutableListOf<String>()
        for ((lineIndex, line) in lines.withIndex()) {
            if (lineIndex == 0 && line.startsWith("index,")) continue
            if (line.isBlank()) continue
            val firstComma = line.indexOf(',')
            val secondComma = line.indexOf(',', firstComma + 1)
            require(firstComma > 0 && secondComma > firstComma) {
                "malformed class map line: $line"
            }
            val index = line.substring(0, firstComma).toInt()
            require(index == labels.size) {
                "class map indexes must be dense: expected ${labels.size}, got $index"
            }
            labels += line.substring(secondComma + 1).trim().removeSurrounding("\"")
        }
        require(labels.isNotEmpty()) { "class map is empty" }
        return labels
    }
}
