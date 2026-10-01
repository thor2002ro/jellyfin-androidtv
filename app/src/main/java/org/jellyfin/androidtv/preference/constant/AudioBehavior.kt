package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class AudioBehavior(
	override val nameRes: Int,
) : PreferenceEnum {
	/**
	 * Directly stream audio without any changes
	 */
	DIRECT_STREAM(R.string.pref_audio_direct),

	/**
	 * Downnmix audio to stereo. Disables the AC3, EAC3 and AAC_LATM audio codecs.
	 */
	DOWNMIX_TO_STEREO(R.string.pref_audio_compat),

	/**
	 * Convert unsupported multichannel audio to a surround codec supported by the active output route.
	 */
	TRANSCODE_TO_PASSTHROUGH(R.string.pref_audio_surround_compat),
}
