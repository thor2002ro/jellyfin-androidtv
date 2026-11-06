package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum
import org.jellyfin.sdk.model.api.ItemSortBy

enum class LiveTvChannelOrder(
	override val nameRes: Int,
	val stringValue: String,
) : PreferenceEnum {
	LAST_PLAYED(R.string.lbl_guide_option_played, ItemSortBy.DATE_PLAYED.serialName),
	CHANNEL_NUMBER(R.string.lbl_guide_option_number, "ChannelNumber"),
	CHANNEL_NAME(R.string.lbl_name, ItemSortBy.NAME.serialName);

	companion object {
		fun fromString(value: String) = entries
			.firstOrNull { it.stringValue.equals(value, ignoreCase = true) }
			?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
			?: when {
				value.equals(ItemSortBy.DATE_PLAYED.name, ignoreCase = true) -> LAST_PLAYED
				value.equals(ItemSortBy.DATE_PLAYED.serialName, ignoreCase = true) -> LAST_PLAYED
				value.equals(ItemSortBy.SORT_NAME.name, ignoreCase = true) -> CHANNEL_NUMBER
				value.equals(ItemSortBy.SORT_NAME.serialName, ignoreCase = true) -> CHANNEL_NUMBER
				value.equals(ItemSortBy.NAME.name, ignoreCase = true) -> CHANNEL_NAME
				value.equals(ItemSortBy.NAME.serialName, ignoreCase = true) -> CHANNEL_NAME
				else -> CHANNEL_NAME
			}
	}
}
