package ru.opencode.mobile.data

/** Сравнение версий без Android и сетевых зависимостей. */
object VersionTools {
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun parse(value: String): List<Int> =
        value.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
