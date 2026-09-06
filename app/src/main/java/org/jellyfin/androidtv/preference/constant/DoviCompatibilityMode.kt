package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.playback.dovi.DoviCompatibilityMode as PlaybackDoviCompatibilityMode
import org.jellyfin.preference.PreferenceEnum

enum class DoviCompatibilityMode(
	override val nameRes: Int,
	val mode: PlaybackDoviCompatibilityMode,
) : PreferenceEnum {
	AUTO(R.string.dovi_compatibility_auto, PlaybackDoviCompatibilityMode.AUTO),
	ALWAYS(R.string.dovi_compatibility_always, PlaybackDoviCompatibilityMode.ALWAYS),
	COMPATIBILITY(R.string.dovi_compatibility_mode, PlaybackDoviCompatibilityMode.COMPATIBILITY),
	OFF(R.string.dovi_compatibility_off, PlaybackDoviCompatibilityMode.OFF),
}
