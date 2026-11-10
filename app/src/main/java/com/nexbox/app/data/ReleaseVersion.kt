package com.nexbox.app.data

/** Numeric stable tags; build metadata does not change ordering. No publication-time comparison. */
internal class ReleaseVersion private constructor(
    val display: String,
    private val components: List<String>
) : Comparable<ReleaseVersion> {
    val identity: String = components.joinToString(".")

    override fun compareTo(other: ReleaseVersion): Int {
        for (index in 0 until maxOf(components.size, other.components.size)) {
            val left = components.getOrElse(index) { "0" }
            val right = other.components.getOrElse(index) { "0" }
            val compared = left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
            if (compared != 0) return compared
        }
        return 0
    }

    companion object {
        private val pattern = Regex("^[vV]?([0-9]+(?:\\.[0-9]+){0,9})(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")

        fun parse(value: String): ReleaseVersion? {
            if (value.length !in 1..120 || value != value.trim()) return null
            val match = pattern.matchEntire(value) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.trimStart('0').ifEmpty { "0" } }
            val normalized = numbers.dropLastWhile { it == "0" }.ifEmpty { listOf("0") }
            return ReleaseVersion(value.removePrefix("v").removePrefix("V"), normalized)
        }
    }
}
