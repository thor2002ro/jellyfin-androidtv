package org.jellyfin.androidtv.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class LibraryViewStyle(
	override val nameRes: Int,
) : PreferenceEnum {
	CARDS(R.string.library_view_style_cards),
	DENSE_LIST(R.string.library_view_style_dense_list),
}
