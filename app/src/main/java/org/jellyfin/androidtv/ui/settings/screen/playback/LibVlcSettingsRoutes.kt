package org.jellyfin.androidtv.ui.settings.screen.playback

import org.jellyfin.androidtv.ui.navigation.RouteComposable

object LibVlcSettingsRoutes {
	const val PLAYBACK_LIBVLC = "/playback/advanced/libvlc"
	const val PLAYBACK_LIBVLC_DECODER = "/playback/advanced/libvlc/decoder"
	const val PLAYBACK_LIBVLC_DEBLOCKING = "/playback/advanced/libvlc/deblocking"
	const val PLAYBACK_LIBVLC_VIDEO_OUTPUT = "/playback/advanced/libvlc/video-output"
	const val PLAYBACK_LIBVLC_AUDIO_OUTPUT = "/playback/advanced/libvlc/audio-output"
	const val PLAYBACK_LIBVLC_REPLAY_GAIN_MODE = "/playback/advanced/libvlc/replay-gain-mode"
}

val libVlcSettingsRoutes = mapOf<String, RouteComposable>(
	LibVlcSettingsRoutes.PLAYBACK_LIBVLC to {
		SettingsPlaybackLibVlcScreen()
	},
	LibVlcSettingsRoutes.PLAYBACK_LIBVLC_DECODER to {
		SettingsPlaybackLibVlcDecoderScreen()
	},
	LibVlcSettingsRoutes.PLAYBACK_LIBVLC_VIDEO_OUTPUT to {
		SettingsPlaybackLibVlcVideoOutputScreen()
	},
	LibVlcSettingsRoutes.PLAYBACK_LIBVLC_AUDIO_OUTPUT to {
		SettingsPlaybackLibVlcAudioOutputScreen()
	},
	LibVlcSettingsRoutes.PLAYBACK_LIBVLC_REPLAY_GAIN_MODE to {
		SettingsPlaybackLibVlcReplayGainModeScreen()
	},
	LibVlcSettingsRoutes.PLAYBACK_LIBVLC_DEBLOCKING to {
		SettingsPlaybackLibVlcDeblockingScreen()
	},
)
