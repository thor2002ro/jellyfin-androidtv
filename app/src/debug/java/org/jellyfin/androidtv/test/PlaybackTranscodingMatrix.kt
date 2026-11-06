package org.jellyfin.androidtv.test

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PlaybackTranscodingCase(
	val id: String,
	val descriptorId: String,
	val variant: PlaybackProfileVariant,
	val selectedAudioCodec: String? = null,
	val selectedSubtitleCodec: String? = null,
)

object PlaybackTranscodingMatrix {
	fun plan(items: Collection<PlaybackMediaDescriptor>): List<PlaybackTranscodingCase> {
		val normalized = items.map(PlaybackMediaDescriptor::normalizedForTranscoding).sortedBy(PlaybackMediaDescriptor::id)
		val video = normalized
			.filter { !it.videoCodec.isNullOrBlank() }
			.distinctBy(PlaybackMediaDescriptor::transcodingVideoSignature)
			.map { item ->
				val signature = item.transcodingVideoSignature()
				PlaybackTranscodingCase(
					id = listOfNotNull(
						"video",
						signature.resolution,
						signature.codec,
						signature.profile,
						"l${signature.level ?: "unknown"}",
						"${signature.bitDepth ?: "unknown"}bit",
						signature.range,
						signature.doviProfile?.let { "dv$it" },
					).joinToString("-").scenarioKey(),
					descriptorId = item.id,
					variant = PlaybackProfileVariant.VIDEO_TRANSCODE,
				)
			}
		val profiles = normalized
			.filter { !it.videoCodec.isNullOrBlank() && !it.videoProfile.isNullOrBlank() }
			.distinctBy { it.videoCodec to it.videoProfile }
			.map { item ->
				val codec = requireNotNull(item.videoCodec)
				val profile = requireNotNull(item.videoProfile)
				PlaybackTranscodingCase(
					id = "profile-${codec.scenarioKey()}-${profile.scenarioKey()}",
					descriptorId = item.id,
					variant = PlaybackProfileVariant.VIDEO_PROFILE_TRANSCODE,
				)
			}
		val audio = normalized.flatMap(PlaybackMediaDescriptor::audioCodecs).toSortedSet().map { codec ->
			val item = requireNotNull(
				normalized.filter { codec in it.audioCodecs }
					.maxWithOrNull(compareBy<PlaybackMediaDescriptor> { it.audioTranscodeFixtureScore() }.thenBy { it.id })
			)
			PlaybackTranscodingCase(
				id = "audio-${codec.scenarioKey()}",
				descriptorId = item.id,
				variant = PlaybackProfileVariant.AUDIO_TRANSCODE,
				selectedAudioCodec = codec,
			)
		}
		val subtitles = normalized.flatMap { item -> item.subtitleCodecs.map { codec -> codec to item } }
			.distinctBy { it.first }
			.map { (codec, item) ->
				PlaybackTranscodingCase(
					id = "subtitle-${codec.scenarioKey()}",
					descriptorId = item.id,
					variant = PlaybackProfileVariant.SUBTITLE_TRANSCODE,
					selectedSubtitleCodec = codec,
				)
			}
		return video + profiles + audio + subtitles
	}

	fun planLivePlayback(selection: PlaybackCatalogSelection): List<PlaybackTranscodingCase> = buildList {
		selection.fixtures["1080p-sdr-avc"]?.let { descriptor ->
			add(PlaybackTranscodingCase("video-playback", descriptor.id, PlaybackProfileVariant.VIDEO_TRANSCODE))
			descriptor.audioCodecs.map(String::lowercase).sorted().firstOrNull()?.let { codec ->
				add(
					PlaybackTranscodingCase(
						id = "audio-playback-${codec.scenarioKey()}",
						descriptorId = descriptor.id,
						variant = PlaybackProfileVariant.AUDIO_TRANSCODE,
						selectedAudioCodec = codec,
					)
				)
			}
		}
		selection.fixtures["1080p-sdr-ass"]?.let { descriptor ->
			descriptor.subtitleCodecs.map(String::lowercase).sorted()
				.firstOrNull { it == "ass" || it == "ssa" }
				?.let { codec ->
					add(
						PlaybackTranscodingCase(
							id = "subtitle-playback-${codec.scenarioKey()}",
							descriptorId = descriptor.id,
							variant = PlaybackProfileVariant.SUBTITLE_TRANSCODE,
							selectedSubtitleCodec = codec,
						)
					)
				}
		}
	}
}

private data class TranscodingVideoSignature(
	val resolution: String,
	val codec: String,
	val profile: String,
	val level: Int?,
	val bitDepth: Int?,
	val range: String,
	val doviProfile: Int?,
)

private fun PlaybackMediaDescriptor.normalizedForTranscoding() = copy(
	videoCodec = videoCodec?.lowercase(),
	videoProfile = videoProfile?.lowercase(),
	videoRange = videoRange?.lowercase(),
	audioCodecs = audioCodecs.mapTo(sortedSetOf(), String::lowercase),
	subtitleCodecs = subtitleCodecs.mapTo(sortedSetOf(), String::lowercase),
)

private fun PlaybackMediaDescriptor.transcodingVideoSignature() = TranscodingVideoSignature(
	resolution = when {
		(width ?: 0) >= 3000 || (height ?: 0) >= 2000 -> "4k"
		(height ?: 0) >= 1000 -> "1080p"
		(height ?: 0) >= 700 -> "720p"
		else -> "${width ?: 0}x${height ?: 0}"
	},
	codec = videoCodec ?: "unknown",
	profile = videoProfile ?: "unknown",
	level = videoLevel,
	bitDepth = bitDepth,
	range = videoRange ?: "unknown",
	doviProfile = doviProfile,
)

private fun PlaybackMediaDescriptor.audioTranscodeFixtureScore(): Int =
	(if (videoCodec == "h264") 100 else if (videoCodec == "hevc") 50 else 0) +
		(if (videoRange == "sdr") 30 else 0) +
		(if ((width ?: Int.MAX_VALUE) <= 1920 && (height ?: Int.MAX_VALUE) <= 1080) 20 else 0) +
		(if (videoProfile == "baseline" || videoProfile == "main" || videoProfile == "high") 10 else 0)

fun transcodeReasons(url: String): Set<String> {
	val query = URI(url).rawQuery ?: return emptySet()
	val encoded = query.split('&').firstNotNullOfOrNull { parameter ->
		val name = parameter.substringBefore('=')
		parameter.substringAfter('=', "").takeIf { name.equals("TranscodeReasons", ignoreCase = true) }
	} ?: return emptySet()
	return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
		.split(',')
		.mapNotNullTo(linkedSetOf()) { it.trim().takeIf(String::isNotEmpty) }
}

private fun String.scenarioKey() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
