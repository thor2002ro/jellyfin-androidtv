package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class PlayerHeaderLayout(
	override val nameRes: Int,
) : PreferenceEnum {
	TEXT_ONLY(R.string.pref_player_header_layout_text_only),
	LOGO_ABOVE_DETAILS(R.string.pref_player_header_layout_logo_above),
	LOGO_BESIDE_DETAILS(R.string.pref_player_header_layout_logo_beside),
}
