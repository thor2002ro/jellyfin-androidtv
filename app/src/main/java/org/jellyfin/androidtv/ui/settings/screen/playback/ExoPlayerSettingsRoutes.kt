package org.jellyfin.androidtv.ui.settings.screen.playback

import org.jellyfin.androidtv.ui.navigation.RouteComposable

object ExoPlayerSettingsRoutes {
	const val PLAYBACK_EXOPLAYER = "/playback/advanced/exoplayer"
}

val exoPlayerSettingsRoutes = mapOf<String, RouteComposable>(
	ExoPlayerSettingsRoutes.PLAYBACK_EXOPLAYER to {
		SettingsPlaybackExoPlayerScreen()
	},
)
