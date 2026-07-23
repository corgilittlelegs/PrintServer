package dev.jaspreet.printserver.scan

object ScanTone {
    const val MIN = 0
    const val MAX = 2000
    const val DEFAULT = 1000

    fun resolve(value: Int?, default: Int = DEFAULT): Int =
        value?.coerceIn(MIN, MAX) ?: default.coerceIn(MIN, MAX)
}
