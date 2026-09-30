package org.jellyfin.androidtv.ui.settings.screen.playback

import org.jellyfin.androidtv.preference.constant.LibMPVChoiceSetting
import org.jellyfin.androidtv.ui.navigation.RouteComposable

object LibMPVSettingsRoutes {
	const val PLAYBACK_MPV = "/playback/advanced/mpv"
	const val PLAYBACK_MPV_CHOICE = "/playback/advanced/mpv/choice/{setting}"
	const val PLAYBACK_MPV_ALL_OPTIONS = "/playback/advanced/mpv/all-options"
}

val mpvSettingsRoutes = mapOf<String, RouteComposable>(
	LibMPVSettingsRoutes.PLAYBACK_MPV to {
		SettingsPlaybackLibMPVScreen()
	},
	LibMPVSettingsRoutes.PLAYBACK_MPV_CHOICE to { context ->
		SettingsPlaybackLibMPVChoiceScreen(
			setting = requireNotNull(LibMPVChoiceSetting.fromSlug(context.parameters["setting"])),
		)
	},
	LibMPVSettingsRoutes.PLAYBACK_MPV_ALL_OPTIONS to {
		SettingsPlaybackLibMPVAllOptionsScreen()
	},
)
