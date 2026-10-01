package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.preference.managedAudioPassthroughMimeTypes
import org.jellyfin.androidtv.preference.playbackBackend
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.util.profile.getSupportedMPVPassthroughAudioMimes
import org.jellyfin.androidtv.util.profile.getSupportedPassthroughAudioMimes
import org.jellyfin.design.Tokens
import org.koin.compose.koinInject

internal data class AudioPassthroughFormat(
	val mimeType: String,
	val label: String,
)

private val passthroughAudioFormatsByFamily = createPassthroughAudioFormatsByFamily()

@OptIn(UnstableApi::class)
private fun createPassthroughAudioFormatsByFamily(): Map<BitstreamAudioFormat, List<AudioPassthroughFormat>> = mapOf(
	BitstreamAudioFormat.AC3 to listOf(
		AudioPassthroughFormat(MimeTypes.AUDIO_AC3, "AC-3"),
	),
	BitstreamAudioFormat.EAC3 to listOf(
		AudioPassthroughFormat(MimeTypes.AUDIO_E_AC3, "E-AC-3"),
		AudioPassthroughFormat(MimeTypes.AUDIO_E_AC3_JOC, "E-AC-3 JOC"),
	),
	BitstreamAudioFormat.DTS to listOf(
		AudioPassthroughFormat(MimeTypes.AUDIO_DTS, "DTS"),
		AudioPassthroughFormat(MimeTypes.AUDIO_DTS_EXPRESS, "DTS Express"),
		AudioPassthroughFormat(MimeTypes.AUDIO_DTS_HD, "DTS-HD"),
		AudioPassthroughFormat(MimeTypes.AUDIO_DTS_UHD_P2, "DTS-UHD"),
	),
	BitstreamAudioFormat.TRUEHD to listOf(
		AudioPassthroughFormat(MimeTypes.AUDIO_TRUEHD, "TrueHD"),
	),
)

internal val passthroughAudioMimeTypes: Set<String> = managedAudioPassthroughMimeTypes

internal val passthroughAudioFormatMimeTypes: Set<String> = passthroughAudioFormatsByFamily.values
	.flatten()
	.mapTo(linkedSetOf()) { format -> format.mimeType }

internal fun supportedPassthroughAudioFormats(
	family: BitstreamAudioFormat,
	supportedMimes: Set<String>?,
): List<AudioPassthroughFormat>? = supportedMimes?.let { mimes ->
	passthroughAudioFormatsByFamily.getValue(family).filter { format -> format.mimeType in mimes }
}

@Composable
internal fun rememberSupportedPassthroughAudioMimes(): Set<String>? {
	val context = LocalContext.current.applicationContext
	val userPreferences = koinInject<UserPreferences>()
	val playbackBackend by rememberPreference(userPreferences, UserPreferences.playbackBackend)
	val supportedMimes by produceState<Set<String>?>(null, context, playbackBackend) {
		value = null
		value = withContext(Dispatchers.IO) {
			if (playbackBackend == PlaybackBackend.MPV) {
				getSupportedMPVPassthroughAudioMimes(context, passthroughAudioMimeTypes)
			} else {
				getSupportedPassthroughAudioMimes(context, passthroughAudioMimeTypes)
			}
		}
	}
	return supportedMimes
}

@Composable
internal fun AudioPassthroughSupportCaption(supportedFormats: List<AudioPassthroughFormat>?) {
	if (supportedFormats == null) {
		Text(stringResource(R.string.loading))
		return
	}
	val supported = supportedFormats.isNotEmpty()
	Text(
		text = if (supported) {
			stringResource(
				R.string.lbl_passthrough_supported_formats,
				supportedFormats.joinToString { format -> format.label }
			)
		} else {
			stringResource(R.string.lbl_passthrough_unsupported)
		},
		color = if (supported) Tokens.Color.colorGreen300 else Tokens.Color.colorRed300,
	)
}
