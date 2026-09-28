package org.jellyfin.androidtv.test

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val playbackCatalogJson = Json { prettyPrint = true }

@Serializable
data class PlaybackAudioDescriptor(
	val codec: String?,
	val profile: String?,
	val channels: Int?,
	val sampleRate: Int?,
	val bitDepth: Int?,
	val bitrate: Int?,
)

@Serializable
data class PlaybackMediaDescriptor(
	val id: String,
	val name: String,
	val container: String?,
	val width: Int?,
	val height: Int?,
	val videoCodec: String?,
	val videoProfile: String?,
	val videoLevel: Int?,
	val bitDepth: Int?,
	val videoRange: String?,
	val doviProfile: Int?,
	val subtitleCodecs: Set<String>,
	val audioCodecs: Set<String>,
	val videoFrameRate: Float? = null,
	val videoRefFrames: Int? = null,
	val videoInterlaced: Boolean? = null,
	val videoAnamorphic: Boolean? = null,
	val videoBitrate: Int? = null,
	val containerBitrate: Int? = null,
	val videoStreamCount: Int = 1,
	val audioStreamCount: Int = 0,
	val audioStreams: List<PlaybackAudioDescriptor> = emptyList(),
)

data class PlaybackCoverage(
	val video: Set<String>,
	val audio: Set<String>,
	val containers: Set<String>,
)

data class PlaybackCatalogSelection(
	val fixtures: Map<String, PlaybackMediaDescriptor>,
	val warnings: List<String>,
	val coverage: PlaybackCoverage,
)

fun playbackMediaCatalogJson(items: List<PlaybackMediaDescriptor>): String = playbackCatalogJson.encodeToString(items)

private data class PlaybackFixtureRule(
	val id: String,
	val matches: (PlaybackMediaDescriptor) -> Boolean,
	val score: (PlaybackMediaDescriptor) -> Int = { 0 },
)

object PlaybackMediaCatalog {
	private val rules = listOf(
		PlaybackFixtureRule("1080p-sdr-avc", { it.is1080p() && it.isSdr() && it.videoCodec.isCodec("h264") }) {
			(if (it.subtitleCodecs.isEmpty()) 100 else 0) +
				(if (it.container.isCodec("mp4")) 10 else 0) +
				(if (it.audioCodecs.any { codec -> codec.isCodec("aac") }) 5 else 0) +
				(if (it.videoProfile.isConventionalAvcProfile()) 1 else 0)
		},
		PlaybackFixtureRule("1080p-sdr-ass", {
			it.is1080p() && it.isSdr() && it.subtitleCodecs.any { codec -> codec.isCodec("ass") || codec.isCodec("ssa") }
		}),
		PlaybackFixtureRule("multi-track", {
			it.is1080p() && it.isSdr() && it.videoCodec.isCodec("h264") &&
				(it.audioStreamCount > 1 || it.subtitleCodecs.isNotEmpty())
		}) {
			it.audioStreamCount * 100 + it.subtitleCodecs.size * 10 + if (it.container.isCodec("mkv")) 1 else 0
		},
		PlaybackFixtureRule("4k-hdr10-hevc", {
			it.is4k() && it.videoCodec.isCodec("hevc") && it.videoRange.equals("HDR10", ignoreCase = true)
		}),
		PlaybackFixtureRule("4k-dv5", { it.is4k() && it.doviProfile == 5 }),
		PlaybackFixtureRule("4k-dv7", { it.is4k() && it.doviProfile == 7 }),
		PlaybackFixtureRule("4k-dv8", { it.is4k() && it.doviProfile == 8 }),
	)

	fun select(items: List<PlaybackMediaDescriptor>): PlaybackCatalogSelection {
		val normalized = items.map(PlaybackMediaDescriptor::normalized)
		val fixtures = rules.mapNotNull { rule ->
			normalized.filter(rule.matches).maxWithOrNull(
				compareBy<PlaybackMediaDescriptor> { rule.score(it) }.thenBy(PlaybackMediaDescriptor::id)
			)?.let { rule.id to it }
		}.toMap()
		return PlaybackCatalogSelection(
			fixtures = fixtures,
			warnings = rules.filter { it.id !in fixtures }.map { "MISSING_FIXTURE ${it.id}" },
			coverage = PlaybackCoverage(
				video = normalized.mapNotNull { item -> item.videoCodec?.let { "$it/${item.videoProfile ?: "unknown"}" } }.toSortedSet(),
				audio = normalized.flatMap(PlaybackMediaDescriptor::audioCodecs).toSortedSet(),
				containers = normalized.mapNotNull(PlaybackMediaDescriptor::container).toSortedSet(),
			),
		)
	}
}

private fun PlaybackMediaDescriptor.normalized() = copy(
	container = container?.lowercase(),
	videoCodec = videoCodec?.lowercase(),
	videoProfile = videoProfile?.lowercase(),
	subtitleCodecs = subtitleCodecs.mapTo(sortedSetOf(), String::lowercase),
	audioCodecs = audioCodecs.mapTo(sortedSetOf(), String::lowercase),
)

private fun PlaybackMediaDescriptor.is1080p() = width in 1280..1920 && height in 720..1080
private fun PlaybackMediaDescriptor.is4k() = (width ?: 0) >= 3000 || (height ?: 0) >= 2000
private fun PlaybackMediaDescriptor.isSdr() = videoRange == null || videoRange.equals("SDR", ignoreCase = true)
private fun String?.isCodec(codec: String) = equals(codec, ignoreCase = true)
private fun String?.isConventionalAvcProfile() = this == "baseline" || this == "main" || this == "high"
