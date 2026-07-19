package org.jellyfin.androidtv.ui.settings.screen.playback

import org.jellyfin.androidtv.ui.navigation.RouteComposable

object LibassSettingsRoutes {
	const val PLAYBACK_LIBASS = "/playback/advanced/libass"
	const val PLAYBACK_LIBASS_RENDER_TYPE = "/playback/advanced/libass/render-type"
	const val PLAYBACK_LIBASS_MAX_RENDER_PIXELS = "/playback/advanced/libass/max-render-pixels"
	const val PLAYBACK_LIBASS_CACHE_SIZE = "/playback/advanced/libass/cache-size"
	const val PLAYBACK_LIBASS_GLYPH_SIZE = "/playback/advanced/libass/glyph-size"
}

val libassSettingsRoutes = mapOf<String, RouteComposable>(
	LibassSettingsRoutes.PLAYBACK_LIBASS to {
		SettingsPlaybackLibassScreen()
	},
	LibassSettingsRoutes.PLAYBACK_LIBASS_RENDER_TYPE to {
		SettingsPlaybackLibassRenderTypeScreen()
	},
	LibassSettingsRoutes.PLAYBACK_LIBASS_MAX_RENDER_PIXELS to {
		SettingsPlaybackLibassMaxRenderPixelsScreen()
	},
	LibassSettingsRoutes.PLAYBACK_LIBASS_CACHE_SIZE to {
		SettingsPlaybackLibassCacheSizeScreen()
	},
	LibassSettingsRoutes.PLAYBACK_LIBASS_GLYPH_SIZE to {
		SettingsPlaybackLibassGlyphSizeScreen()
	},
)
