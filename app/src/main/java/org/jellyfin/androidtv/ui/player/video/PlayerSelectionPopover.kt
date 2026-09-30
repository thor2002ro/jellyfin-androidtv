package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.popover.Popover

@Composable
internal fun PlayerSelectionPopover(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	title: String,
	icon: ImageVector? = null,
	wide: Boolean = false,
	content: @Composable ColumnScope.() -> Unit,
) {
	val popupOffsetY = dimensionResource(R.dimen.player_popup_menu_offset_y)
	val popupPaddingHorizontal = dimensionResource(R.dimen.player_popup_menu_padding_horizontal)
	val popupPaddingVertical = dimensionResource(R.dimen.player_popup_menu_padding_vertical)
	val popupMinWidth = dimensionResource(
		if (wide) R.dimen.player_popup_menu_wide_min_width else R.dimen.player_popup_menu_standard_min_width
	)
	val popupMaxWidth = dimensionResource(
		if (wide) R.dimen.player_popup_menu_wide_max_width else R.dimen.player_popup_menu_standard_max_width
	)
	val popupMaxHeight = dimensionResource(R.dimen.player_popup_menu_max_height)

	Popover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, -popupOffsetY),
	) {
		Column(
			verticalArrangement = Arrangement.spacedBy(4.dp),
			modifier = Modifier
				.padding(horizontal = popupPaddingHorizontal, vertical = popupPaddingVertical)
				.widthIn(min = popupMinWidth, max = popupMaxWidth)
				.heightIn(max = popupMaxHeight)
				.verticalScroll(rememberScrollState())
		) {
			PlayerSelectionHeader(title, icon)
			content()
		}
	}
}

@Composable
private fun PlayerSelectionHeader(
	title: String,
	icon: ImageVector?,
) {
	val style = JellyfinTheme.typography.listHeader.copy(
		color = JellyfinTheme.colorScheme.listHeader,
		fontSize = 13.sp,
	)

	if (icon == null) {
		Text(
			text = title,
			style = style,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
		)
	} else {
		Row(
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 6.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				modifier = Modifier.size(20.dp),
			)
			Text(
				text = title,
				style = style,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
internal fun PlayerSelectionItem(
	label: String,
	isSelected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val itemMinHeight = dimensionResource(R.dimen.player_popup_menu_item_min_height)

	ListButton(
		onClick = onClick,
		headingContent = {
			Text(
				text = label,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		},
		leadingContent = {
			RadioButton(checked = isSelected)
		},
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = itemMinHeight),
	)
}
