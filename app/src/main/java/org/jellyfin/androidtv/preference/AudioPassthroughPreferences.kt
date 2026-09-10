@file:JvmName("AudioPassthroughPreferences")

package org.jellyfin.androidtv.preference

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
fun UserPreferences.isAudioPassthroughEnabled(mimeType: String): Boolean = when (mimeType) {
	MimeTypes.AUDIO_AC3 -> this[UserPreferences.ac3Enabled]
	MimeTypes.AUDIO_E_AC3,
	MimeTypes.AUDIO_E_AC3_JOC -> this[UserPreferences.eac3Enabled]
	MimeTypes.AUDIO_DTS,
	MimeTypes.AUDIO_DTS_EXPRESS,
	MimeTypes.AUDIO_DTS_HD,
	MimeTypes.AUDIO_DTS_UHD_P2 -> this[UserPreferences.dtsEnabled]
	MimeTypes.AUDIO_TRUEHD -> this[UserPreferences.truehdEnabled]
	else -> true
}
