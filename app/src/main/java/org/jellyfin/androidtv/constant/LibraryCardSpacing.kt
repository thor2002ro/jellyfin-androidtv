package org.jellyfin.androidtv.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum
import kotlin.math.roundToInt

enum class LibraryCardSpacing(
	override val nameRes: Int,
) : PreferenceEnum {
	COMPACT(R.string.library_card_spacing_compact),
	NORMAL(R.string.library_card_spacing_normal),
	RELAXED(R.string.library_card_spacing_relaxed),
	;

	fun apply(baseDp: Int): Int = when (this) {
		COMPACT -> (baseDp * COMPACT_FACTOR).roundToInt().coerceAtLeast(MINIMUM_COMPACT_SPACING_DP)
		NORMAL -> baseDp
		RELAXED -> (baseDp * RELAXED_FACTOR).roundToInt()
	}

	private companion object {
		const val COMPACT_FACTOR = 0.75f
		const val RELAXED_FACTOR = 1.25f
		const val MINIMUM_COMPACT_SPACING_DP = 2
	}
}
