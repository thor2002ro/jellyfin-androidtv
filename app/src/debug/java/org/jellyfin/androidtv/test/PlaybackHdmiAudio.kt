package org.jellyfin.androidtv.test

data class PlaybackHdmiAudioCase(
	val id: String,
	val descriptorId: String,
	val codec: String,
)

object PlaybackHdmiAudioMatrix {
	private val aliases = mapOf(
		"ac3" to setOf("ac3"),
		"eac3" to setOf("eac3", "eac3_joc"),
		"dts" to setOf("dts"),
		"dtshd" to setOf("dts_hd", "dts-hd", "dtshd"),
		"truehd" to setOf("truehd"),
		"ac4" to setOf("ac4"),
	)

	fun plan(items: Collection<PlaybackMediaDescriptor>, supportedCodecs: Set<String>): List<PlaybackHdmiAudioCase> =
		supportedCodecs.mapNotNull { supported ->
			val accepted = aliases[supported].orEmpty()
			items.filter { item -> item.audioStreams.any { it.codec?.lowercase() in accepted } }
				.maxWithOrNull(compareBy<PlaybackMediaDescriptor> { it.hdmiFixtureScore() }.thenBy { it.id })
				?.let { PlaybackHdmiAudioCase("passthrough-$supported", it.id, supported) }
		}

	fun codecMatches(expected: String, observed: String?): Boolean =
		observed?.lowercase()?.replace(Regex("[^a-z0-9]+"), "")?.let { normalized ->
			aliases[expected].orEmpty().any { alias ->
				val normalizedAlias = alias.replace(Regex("[^a-z0-9]+"), "")
				normalized == normalizedAlias || normalized.contains(normalizedAlias)
			}
		} == true
}

private fun PlaybackMediaDescriptor.hdmiFixtureScore() =
	(if (videoCodec.equals("h264", true)) 100 else 0) +
		(if (videoRange.equals("SDR", true)) 40 else 0) +
		(if ((width ?: Int.MAX_VALUE) <= 1920 && (height ?: Int.MAX_VALUE) <= 1080) 20 else 0)
