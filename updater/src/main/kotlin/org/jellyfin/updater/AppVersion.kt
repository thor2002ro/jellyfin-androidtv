package org.jellyfin.updater

internal data class AppVersion(private val parts: List<Int>) : Comparable<AppVersion> {
	override fun compareTo(other: AppVersion): Int {
		val size = maxOf(parts.size, other.parts.size)
		for (index in 0 until size) {
			val left = parts.getOrElse(index) { 0 }
			val right = other.parts.getOrElse(index) { 0 }
			if (left != right) return left.compareTo(right)
		}
		return 0
	}

	companion object {
		private val dateVersion = Regex("""^v?(\d{4})\.(\d{2})\.(\d{2})\.(\d{2})(\d{2})(?:[-+].*)?$""")
		private val semanticVersion = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

		fun parse(input: String): AppVersion? {
			dateVersion.matchEntire(input)?.let { match ->
				return AppVersion(match.groupValues.drop(1).map(String::toInt))
			}

			semanticVersion.matchEntire(input)?.let { match ->
				return AppVersion(listOf(0) + match.groupValues.drop(1).map(String::toInt))
			}

			return null
		}
	}
}
