package org.jellyfin.androidtv.test

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val playbackTestJson = Json { prettyPrint = true }

data class PlaybackTestArguments(
	val suites: Set<String>,
	val backend: String?,
	val scenario: String?,
	val testUser: String,
	val testFolder: String,
	val soakIterations: Int,
) {
	companion object {
		private val allSuites = setOf("resume", "server", "transcode", "backend", "soak", "recovery", "hdmi-audio")

		fun from(values: Map<String, String?>): PlaybackTestArguments {
			fun value(key: String) = values[key]?.trim()?.takeIf(String::isNotEmpty)
			val suite = value("suite")
			return PlaybackTestArguments(
				suites = if (suite == null || suite == "all") allSuites else setOf(suite),
				backend = value("backend"),
				scenario = value("scenario"),
				testUser = value("testUser") ?: "androidtv-playback-test",
				testFolder = value("testFolder") ?: "Test Videos",
				soakIterations = value("soakIterations")?.toIntOrNull()?.coerceIn(2, 50) ?: 3,
			)
		}
	}
}

@Serializable
enum class PlaybackTestStatus { PASS, FAIL, WARN, SKIP }

@Serializable
data class PlaybackTestResult(
	val status: PlaybackTestStatus,
	val suite: String,
	val backend: String? = null,
	val scenario: String? = null,
	val detail: String = "",
) {
	fun line(): String = buildList {
		add(status.name)
		add(listOfNotNull(suite, backend, scenario).joinToString("/"))
		if (detail.isNotBlank()) add(redactPlaybackTestSecrets(detail))
	}.joinToString(" ")

	fun sanitized() = copy(detail = redactPlaybackTestSecrets(detail))
}

@Serializable
data class PlaybackTestReport(
	val counts: Map<String, Int>,
	val results: List<PlaybackTestResult>,
)

class PlaybackTestSummary(val results: List<PlaybackTestResult>) {
	val counts: Map<String, Int> = PlaybackTestStatus.entries.associate { status ->
		status.name.lowercase() to results.count { it.status == status }
	}
	val failed: Boolean get() = counts.getValue("fail") > 0

	fun toJson(): String = playbackTestJson.encodeToString(
		PlaybackTestReport(counts, results.map(PlaybackTestResult::sanitized))
	)
}

internal fun redactPlaybackTestSecrets(value: String): String = value
	.replace(Regex("(?i)(Authorization\\s*:\\s*Bearer\\s+)[^\\s]+"), "$1<redacted>")
	.replace(Regex("(?i)([?&](?:api_key|apiKey|access_token)=)[^&\\s]+"), "$1<redacted>")
