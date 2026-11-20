package org.jellyfin.androidtv.ui.settings.screen.screensaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsScreensaverScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings_title).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_screensaver)) },
			)
		}

		item {
			var screensaverInAppEnabled by rememberPreference(userPreferences, UserPreferences.screensaverInAppEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_inapp_enabled)) },
				trailingContent = { Checkbox(checked = screensaverInAppEnabled) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_inapp_enabled_description)) },
				onClick = { screensaverInAppEnabled = !screensaverInAppEnabled },
				modifier = Modifier.focusKey("screensaver_in_app_enabled")
			)
		}

		item {
			var screensaverInAppTimeout by rememberPreference(userPreferences, UserPreferences.screensaverInAppTimeout)
			val caption = getScreensaverTimeoutOptions()
				.firstOrNull { (duration) -> duration.inWholeMilliseconds == screensaverInAppTimeout }
				?.second.orEmpty()

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_inapp_timeout)) },
				captionContent = { Text(caption) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_TIMEOUT) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER_TIMEOUT)
			)
		}

		item {
			var screensaverAgeRatingRequired by rememberPreference(userPreferences, UserPreferences.screensaverAgeRatingRequired)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_ageratingrequired_title)) },
				trailingContent = { Checkbox(checked = screensaverAgeRatingRequired) },
				captionContent = {
					val caption = if (screensaverAgeRatingRequired) R.string.pref_screensaver_ageratingrequired_enabled
					else R.string.pref_screensaver_ageratingrequired_disabled
					Text(stringResource(caption))
				},
				onClick = { screensaverAgeRatingRequired = !screensaverAgeRatingRequired },
				modifier = Modifier.focusKey("screensaver_age_rating_required")
			)
		}

		item {
			val screensaverAgeRatingMax by rememberPreference(userPreferences, UserPreferences.screensaverAgeRatingMax)
			val caption = getScreensaverAgeRatingOptions()
				.firstOrNull { (ageRating) -> ageRating == screensaverAgeRatingMax }
				?.second.orEmpty()

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_ageratingmax)) },
				captionContent = { Text(caption) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_AGE_RATING) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER_AGE_RATING)
			)
		}

		item {
			val motion by rememberPreference(userPreferences, UserPreferences.screensaverMotion)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_motion)) },
				captionContent = { Text(stringResource(motion.nameRes)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_MOTION) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER_MOTION),
			)
		}

		item {
			val transition by rememberPreference(userPreferences, UserPreferences.screensaverTransition)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_transition)) },
				captionContent = { Text(stringResource(transition.nameRes)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_TRANSITION) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER_TRANSITION),
			)
		}

		item {
			var bouncingLogo by rememberPreference(userPreferences, UserPreferences.screensaverBouncingLogo)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_bouncing_logo)) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_bouncing_logo_description)) },
				trailingContent = { Checkbox(checked = bouncingLogo) },
				onClick = { bouncingLogo = !bouncingLogo },
				modifier = Modifier.focusKey("screensaver_bouncing_logo"),
			)
		}

		item {
			var snow by rememberPreference(userPreferences, UserPreferences.screensaverSnow)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_snow)) },
				trailingContent = { Checkbox(checked = snow) },
				onClick = { snow = !snow },
				modifier = Modifier.focusKey("screensaver_snow"),
			)
		}

		item {
			val source by rememberPreference(userPreferences, UserPreferences.screensaverBackdropSource)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_source)) },
				captionContent = { Text(stringResource(source.nameRes)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_SOURCE) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER_SOURCE),
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_weather)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_WEATHER) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER_WEATHER),
			)
		}
	}
}
