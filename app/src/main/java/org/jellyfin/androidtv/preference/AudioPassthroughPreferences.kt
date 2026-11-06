@file:JvmName("AudioPassthroughPreferences")

package org.jellyfin.androidtv.preference

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.jellyfin.androidtv.preference.constant.BitstreamAudioMode

@OptIn(UnstableApi::class)
internal val managedAudioPassthroughMimeTypes = setOf(
	MimeTypes.AUDIO_AC3,
	MimeTypes.AUDIO_E_AC3,
	MimeTypes.AUDIO_E_AC3_JOC,
	MimeTypes.AUDIO_DTS,
	MimeTypes.AUDIO_DTS_EXPRESS,
	MimeTypes.AUDIO_DTS_HD,
	MimeTypes.AUDIO_DTS_UHD_P2,
	MimeTypes.AUDIO_TRUEHD,
)

@OptIn(UnstableApi::class)
fun UserPreferences.isAudioPassthroughEnabled(mimeType: String): Boolean = when (mimeType) {
	MimeTypes.AUDIO_AC3 -> this[UserPreferences.bitstreamAc3] != BitstreamAudioMode.DISABLE
	MimeTypes.AUDIO_E_AC3,
	MimeTypes.AUDIO_E_AC3_JOC -> this[UserPreferences.bitstreamAc3] != BitstreamAudioMode.DISABLE &&
		this[UserPreferences.bitstreamEac3] != BitstreamAudioMode.DISABLE
	MimeTypes.AUDIO_DTS,
	MimeTypes.AUDIO_DTS_EXPRESS,
	MimeTypes.AUDIO_DTS_HD,
	MimeTypes.AUDIO_DTS_UHD_P2 -> this[UserPreferences.bitstreamDts] != BitstreamAudioMode.DISABLE
	MimeTypes.AUDIO_TRUEHD -> this[UserPreferences.bitstreamTrueHd] != BitstreamAudioMode.DISABLE
	else -> true
}
