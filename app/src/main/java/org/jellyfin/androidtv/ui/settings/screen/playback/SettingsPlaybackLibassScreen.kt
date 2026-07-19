package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.LibassCacheSize
import org.jellyfin.androidtv.preference.constant.LibassGlyphSize
import org.jellyfin.androidtv.preference.constant.LibassMaxRenderPixels
import org.jellyfin.androidtv.preference.constant.LibassRenderType
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackLibassScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var assDirectPlay by rememberPreference(userPreferences, UserPreferences.assDirectPlay)
	var renderType by rememberPreference(userPreferences, UserPreferences.libassRenderType)
	var maxRenderPixels by rememberPreference(userPreferences, UserPreferences.libassMaxRenderPixels)
	var cacheSize by rememberPreference(userPreferences, UserPreferences.libassCacheSize)
	var glyphSize by rememberPreference(userPreferences, UserPreferences.libassGlyphSize)
	var parseSubtitlesDuringExtraction by rememberPreference(userPreferences, UserPreferences.libassParseSubtitlesDuringExtraction)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback_advanced).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_libass_options)) },
				captionContent = { Text(stringResource(R.string.preference_libass_options_description)) },
			)
		}

		item {
			val description = stringResource(R.string.preference_libass_enable_description)

			ListButton(
				headingContent = { Text(stringResource(R.string.preference_enable_ass)) },
				captionContent = { Text(description) },
				trailingContent = { Checkbox(checked = assDirectPlay) },
				onClick = { assDirectPlay = !assDirectPlay },
			)
		}

		item {
			val description = stringResource(renderType.descriptionRes)

			ListButton(
				overlineContent = { Text(stringResource(renderType.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libass_render_type)) },
				captionContent = { Text(description) },
				onClick = { router.push(LibassSettingsRoutes.PLAYBACK_LIBASS_RENDER_TYPE) },
			)
		}

		item {
			val description = stringResource(maxRenderPixels.descriptionRes)

			ListButton(
				overlineContent = { Text(stringResource(maxRenderPixels.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libass_max_render_pixels)) },
				captionContent = { Text(description) },
				onClick = { router.push(LibassSettingsRoutes.PLAYBACK_LIBASS_MAX_RENDER_PIXELS) },
			)
		}

		item {
			val description = stringResource(cacheSize.descriptionRes)

			ListButton(
				overlineContent = { Text(stringResource(cacheSize.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libass_cache_size)) },
				captionContent = { Text(description) },
				onClick = { router.push(LibassSettingsRoutes.PLAYBACK_LIBASS_CACHE_SIZE) },
			)
		}

		item {
			val description = stringResource(glyphSize.descriptionRes)

			ListButton(
				overlineContent = { Text(stringResource(glyphSize.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libass_glyph_cache_size)) },
				captionContent = { Text(description) },
				onClick = { router.push(LibassSettingsRoutes.PLAYBACK_LIBASS_GLYPH_SIZE) },
			)
		}

		item {
			val description = stringResource(R.string.preference_libass_parse_subtitles_during_extraction_description)
			val offsetWarning = stringResource(R.string.preference_libass_parse_subtitles_during_extraction_offset_warning)

			ListButton(
				headingContent = { Text(stringResource(R.string.preference_libass_parse_subtitles_during_extraction)) },
				captionContent = {
					Column {
						Text(description)
						Text(offsetWarning, color = JellyfinTheme.colorScheme.recording)
					}
				},
				trailingContent = { Checkbox(checked = parseSubtitlesDuringExtraction) },
				onClick = { parseSubtitlesDuringExtraction = !parseSubtitlesDuringExtraction },
			)
		}
	}
}

@Composable
fun SettingsPlaybackLibassRenderTypeScreen() {
	val userPreferences = koinInject<UserPreferences>()
	var renderType by rememberPreference(userPreferences, UserPreferences.libassRenderType)

	SettingsPlaybackLibassOptionScreen(
		headingRes = R.string.preference_libass_render_type,
		descriptionRes = R.string.preference_libass_render_type_description,
		entries = LibassRenderType.entries,
		selected = renderType,
		nameRes = LibassRenderType::nameRes,
		descriptionResFor = LibassRenderType::descriptionRes,
		onSelected = { renderType = it },
	)
}

@Composable
fun SettingsPlaybackLibassMaxRenderPixelsScreen() {
	val userPreferences = koinInject<UserPreferences>()
	var maxRenderPixels by rememberPreference(userPreferences, UserPreferences.libassMaxRenderPixels)

	SettingsPlaybackLibassOptionScreen(
		headingRes = R.string.preference_libass_max_render_pixels,
		descriptionRes = R.string.preference_libass_max_render_pixels_description,
		entries = LibassMaxRenderPixels.entries,
		selected = maxRenderPixels,
		nameRes = LibassMaxRenderPixels::nameRes,
		descriptionResFor = LibassMaxRenderPixels::descriptionRes,
		onSelected = { maxRenderPixels = it },
	)
}

@Composable
fun SettingsPlaybackLibassCacheSizeScreen() {
	val userPreferences = koinInject<UserPreferences>()
	var cacheSize by rememberPreference(userPreferences, UserPreferences.libassCacheSize)

	SettingsPlaybackLibassOptionScreen(
		headingRes = R.string.preference_libass_cache_size,
		descriptionRes = R.string.preference_libass_cache_size_description,
		entries = LibassCacheSize.entries,
		selected = cacheSize,
		nameRes = LibassCacheSize::nameRes,
		descriptionResFor = LibassCacheSize::descriptionRes,
		onSelected = { cacheSize = it },
	)
}

@Composable
fun SettingsPlaybackLibassGlyphSizeScreen() {
	val userPreferences = koinInject<UserPreferences>()
	var glyphSize by rememberPreference(userPreferences, UserPreferences.libassGlyphSize)

	SettingsPlaybackLibassOptionScreen(
		headingRes = R.string.preference_libass_glyph_cache_size,
		descriptionRes = R.string.preference_libass_glyph_cache_size_description,
		entries = LibassGlyphSize.entries,
		selected = glyphSize,
		nameRes = LibassGlyphSize::nameRes,
		descriptionResFor = LibassGlyphSize::descriptionRes,
		onSelected = { glyphSize = it },
	)
}

@Composable
private fun <T : Any> SettingsPlaybackLibassOptionScreen(
	headingRes: Int,
	descriptionRes: Int,
	entries: List<T>,
	selected: T,
	nameRes: (T) -> Int,
	descriptionResFor: (T) -> Int,
	onSelected: (T) -> Unit,
) {
	val router = LocalRouter.current

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.preference_libass_options).uppercase()) },
				headingContent = { Text(stringResource(headingRes)) },
				captionContent = { Text(stringResource(descriptionRes)) },
			)
		}

		items(entries) { entry ->
			val description = stringResource(descriptionResFor(entry))

			ListButton(
				headingContent = { Text(stringResource(nameRes(entry))) },
				captionContent = { Text(description) },
				trailingContent = { RadioButton(checked = selected == entry) },
				onClick = {
					onSelected(entry)
					router.back()
				},
			)
		}
	}
}
