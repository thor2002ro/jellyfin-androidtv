package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsHomeSectionScreen(index: Int) {
	val router = LocalRouter.current
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	val scope = rememberCoroutineScope()
	val sections = userSettingPreferences.homesections.map(userSettingPreferences::get)
	val activeSections = sections.filterNot { it == HomeSectionType.NONE }
	val selectedSection = activeSections.getOrNull(index)
	val addingSection = index == activeSections.size && activeSections.size < sections.size

	if (selectedSection == null && !addingSection) {
		ListMessage {
			Text("Unknown section $index")
		}

		return
	}

	val saveSections: (List<HomeSectionType>) -> Unit = { updatedSections ->
		userSettingPreferences.homesections.forEachIndexed { preferenceIndex, preference ->
			userSettingPreferences[preference] = updatedSections[preferenceIndex]
		}
		scope.launch {
			userSettingPreferences.commit()
			router.back()
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.home_prefs).uppercase()) },
				headingContent = { Text(stringResource(R.string.home_section_i, index + 1)) },
			)
		}

		if (selectedSection != null) {
			item {
				ListSection(headingContent = { Text(stringResource(R.string.home_section_actions)) })
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.home_section_move_up)) },
					onClick = { saveSections(moveHomeSection(sections, index, -1)) },
					enabled = index > 0,
					modifier = Modifier.focusKey("home_section_move_up")
				)
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.home_section_move_down)) },
					onClick = { saveSections(moveHomeSection(sections, index, 1)) },
					enabled = index < activeSections.lastIndex,
					modifier = Modifier.focusKey("home_section_move_down")
				)
			}

			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.home_section_remove)) },
					onClick = { saveSections(removeHomeSection(sections, index)) },
					modifier = Modifier.focusKey("home_section_remove")
				)
			}
		}

		item {
			ListSection(headingContent = { Text(stringResource(R.string.home_section_type)) })
		}

		items(HomeSectionType.entries.filterNot { it == HomeSectionType.NONE }) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = selectedSection == entry) },
				onClick = { saveSections(setHomeSection(sections, index, entry)) },
				modifier = Modifier
					.focusKey(
						key = "section_type_${entry.name}",
						initialFocus = addingSection && entry == HomeSectionType.entries.first(),
					)
			)
		}
	}
}
