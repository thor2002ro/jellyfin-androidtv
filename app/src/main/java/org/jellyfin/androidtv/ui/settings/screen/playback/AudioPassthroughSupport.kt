package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

internal enum class AudioPassthroughFamily {
	AC3,
	EAC3,
	DTS,
	TRUEHD,
}

internal data class AudioPassthroughFormat(
	val mimeType: String,
	val label: String,
)

private val passthroughAudioFormatsByFamily = createPassthroughAudioFormatsByFamily()

@OptIn(UnstableApi::class)
private fun createPassthroughAudioFormatsByFamily(): Map<AudioPassthroughFamily, List<AudioPassthroughFormat>> = mapOf(
	AudioPassthroughFamily.AC3 to listOf(
		AudioPassthroughFormat(MimeTypes.AUDIO_AC3, "AC-3"),
	),
	AudioPassthroughFamily.EAC3 to listOf(
		AudioPassthroughFormat(MimeTypes.AUDIO_E_AC3, "E-AC3"),
		AudioPassthroughFormat(MimeTypes.AUDIO_E_AC3_JOC, "E-AC3 JOC"),
	),
	AudioPassthroughFamily.DTS to listOf(
		AudioPassthroughFormat(MimeTypes.AUDIO_DTS, "DTS"),
		AudioPassthroughFormat(MimeTypes.AUDIO_DTS_EXPRESS, "DTS Express"),
		AudioPassthroughFormat(MimeTypes.AUDIO_DTS_HD, "DTS-HD"),
		AudioPassthroughFormat(MimeTypes.AUDIO_DTS_UHD_P2, "DTS-UHD"),
	),
	AudioPassthroughFamily.TRUEHD to listOf(
		AudioPassthroughFormat(MimeTypes.AUDIO_TRUEHD, "TrueHD"),
	),
)

internal val passthroughAudioMimeTypes: Set<String> = passthroughAudioFormatsByFamily.values
	.flatten()
	.mapTo(linkedSetOf()) { format -> format.mimeType }

internal fun supportedPassthroughAudioFormats(
	family: AudioPassthroughFamily,
	supportedMimes: Set<String>,
): List<AudioPassthroughFormat> = passthroughAudioFormatsByFamily.getValue(family)
	.filter { format -> format.mimeType in supportedMimes }
