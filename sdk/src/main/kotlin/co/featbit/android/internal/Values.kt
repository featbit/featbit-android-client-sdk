package co.featbit.android.internal

import co.featbit.android.api.*
import co.featbit.android.datasource.FlagRecord
import java.util.Collections

internal object BootstrapRecords {
    fun create(flags: List<BootstrapFlag>): List<FlagRecord>? {
        val keys = HashSet<String>()
        if (flags.any { !keys.add(it.key) }) return null
        return flags.map {
            FlagRecord.builder(it.key, it.value, it.type.name.lowercase(java.util.Locale.ROOT), 0)
                .build()
                .value!!
        }
    }
}

internal fun <K, V> frozen(map: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(map))

internal object Conversion {
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    fun number(raw: String): Double? =
        raw.trim().let {
            if (decimal.matches(it)) it.toDoubleOrNull()?.takeIf(Double::isFinite) else null
        }

    fun boolean(raw: String): Boolean? =
        when {
            raw.equals("true", true) -> true
            raw.equals("false", true) -> false
            else -> null
        }

    fun generic(record: FlagRecord): FbValue? =
        when (record.variationType) {
            "boolean" -> boolean(record.variation)?.let(FbValue::ofBoolean)
            "number" -> number(record.variation)?.let { FbValue.ofNumber(it).value }
            "string" -> FbValue.ofString(record.variation).value
            "json" -> json(record.variation)
            else -> null
        }

    fun json(raw: String): FbValue? =
        try {
            JsonReader(raw).parse()
        } catch (_: IllegalArgumentException) {
            null
        }
}

/** Strict JSON grammar with bounded depth; no dependency types escape. */
private class JsonReader(private val text: String) {
    private var index = 0

    fun parse(): FbValue {
        val result = value(0)
        whitespace()
        require(index == text.length)
        return result
    }

    private fun whitespace() {
        while (index < text.length && text[index] in " \r\n\t") index++
    }

    private fun take(c: Char): Boolean {
        whitespace()
        return if (index < text.length && text[index] == c) {
            index++
            true
        } else false
    }

    private fun value(depth: Int): FbValue {
        require(depth <= 64)
        whitespace()
        require(index < text.length)
        return when (text[index]) {
            '"' -> FbValue.ofString(string()).value!!
            't' -> {
                literal("true")
                FbValue.ofBoolean(true)
            }
            'f' -> {
                literal("false")
                FbValue.ofBoolean(false)
            }
            'n' -> {
                literal("null")
                FbValue.jsonNull()
            }
            '[' -> {
                index++
                val items = ArrayList<FbValue?>()
                if (!take(']')) {
                    do {
                        items.add(value(depth + 1))
                    } while (take(','))
                    require(take(']'))
                }
                FbValue.ofArray(items).value!!
            }
            '{' -> {
                index++
                val items = LinkedHashMap<String?, FbValue?>()
                if (!take('}')) {
                    do {
                        whitespace()
                        val key = string()
                        require(take(':'))
                        items[key] = value(depth + 1)
                    } while (take(','))
                    require(take('}'))
                }
                FbValue.ofObject(items).value!!
            }
            else -> {
                val start = index
                if (index < text.length && text[index] == '-') index++
                require(index < text.length)
                if (text[index] == '0') index++
                else {
                    require(text[index] in '1'..'9')
                    digits()
                }
                if (index < text.length && text[index] == '.') {
                    index++
                    require(digits())
                }
                if (index < text.length && text[index] in "eE") {
                    index++
                    if (index < text.length && text[index] in "+-") index++
                    require(digits())
                }
                FbValue.ofNumber(text.substring(start, index).toDoubleOrNull() ?: errorValue())
                    .value ?: errorValue()
            }
        }
    }

    private fun errorValue(): Nothing = throw IllegalArgumentException()

    private fun digits(): Boolean {
        val start = index
        while (index < text.length && text[index] in '0'..'9') index++
        return index > start
    }

    private fun literal(value: String) {
        require(text.startsWith(value, index))
        index += value.length
    }

    private fun string(): String {
        require(index < text.length && text[index++] == '"')
        val result = StringBuilder()
        while (index < text.length) {
            val c = text[index++]
            if (c == '"') return result.toString()
            require(c.code >= 32)
            if (c != '\\') {
                result.append(c)
                continue
            }
            require(index < text.length)
            result.append(
                when (val escape = text[index++]) {
                    '"',
                    '\\',
                    '/' -> escape
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        require(index + 4 <= text.length)
                        val hex = text.substring(index, index + 4)
                        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                        index += 4
                        hex.toInt(16).toChar()
                    }
                    else -> errorValue()
                }
            )
        }
        errorValue()
    }
}
