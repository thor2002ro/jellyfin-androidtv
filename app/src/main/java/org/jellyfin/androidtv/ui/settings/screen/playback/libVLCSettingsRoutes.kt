package org.jellyfin.androidtv.ui.settings.screen.playback

import org.jellyfin.androidtv.ui.navigation.RouteComposable

object LibVLCSettingsRoutes {
	const val PLAYBACK_LIBVLC = "/playback/advanced/libvlc"
	const val PLAYBACK_LIBVLC_DECODER = "/playback/advanced/libvlc/decoder"
	const val PLAYBACK_LIBVLC_DEBLOCKING = "/playback/advanced/libvlc/deblocking"
	const val PLAYBACK_LIBVLC_VIDEO_OUTPUT = "/playback/advanced/libvlc/video-output"
	const val PLAYBACK_LIBVLC_AUDIO_OUTPUT = "/playback/advanced/libvlc/audio-output"
	const val PLAYBACK_LIBVLC_REPLAY_GAIN_MODE = "/playback/advanced/libvlc/replay-gain-mode"
}

val libVLCSettingsRoutes = mapOf<String, RouteComposable>(
	LibVLCSettingsRoutes.PLAYBACK_LIBVLC to {
		SettingsPlaybackLibVLCScreen()
	},
	LibVLCSettingsRoutes.PLAYBACK_LIBVLC_DECODER to {
		SettingsPlaybackLibVLCDecoderScreen()
	},
	LibVLCSettingsRoutes.PLAYBACK_LIBVLC_VIDEO_OUTPUT to {
		SettingsPlaybackLibVLCVideoOutputScreen()
	},
	LibVLCSettingsRoutes.PLAYBACK_LIBVLC_AUDIO_OUTPUT to {
		SettingsPlaybackLibVLCAudioOutputScreen()
	},
	LibVLCSettingsRoutes.PLAYBACK_LIBVLC_REPLAY_GAIN_MODE to {
		SettingsPlaybackLibVLCReplayGainModeScreen()
	},
	LibVLCSettingsRoutes.PLAYBACK_LIBVLC_DEBLOCKING to {
		SettingsPlaybackLibVLCDeblockingScreen()
	},
)
