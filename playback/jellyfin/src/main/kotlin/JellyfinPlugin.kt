package org.jellyfin.playback.jellyfin

import androidx.lifecycle.Lifecycle
import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackRecoveryService
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackResetService
import org.jellyfin.playback.jellyfin.lyrics.LyricsPlayerService
import org.jellyfin.playback.jellyfin.mediasegment.MediaSegmentService
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamOptions
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamResolver
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.playback.jellyfin.playsession.PlaySessionSocketService
import org.jellyfin.playback.jellyfin.recovery.NetworkPlaybackRecoveryService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaSegmentType

typealias JellyfinMediaStreamOptionsProvider = (BaseItemDto, String?) -> JellyfinMediaStreamOptions

fun jellyfinPlugin(
	api: ApiClient,
	deviceProfileBuilder: () -> DeviceProfile,
	mediaStreamOptionsProvider: JellyfinMediaStreamOptionsProvider = { _, _ ->
		JellyfinMediaStreamOptions()
	},
	mediaSegmentSkipTypes: Set<MediaSegmentType> = emptySet(),
	lifecycle: Lifecycle? = null,
	liveTvDirectPlayEnabled: () -> Boolean = { true },
	networkAvailable: () -> Boolean = { true },
) = playbackPlugin {
	val liveTvPlaybackPolicy = LiveTvPlaybackPolicy(liveTvDirectPlayEnabled)

	provide(JellyfinMediaStreamResolver(api, deviceProfileBuilder, mediaStreamOptionsProvider, liveTvPlaybackPolicy))
	provide(NetworkPlaybackRecoveryService(liveTvPlaybackPolicy, networkAvailable))
	provide(LiveTvPlaybackRecoveryService(liveTvPlaybackPolicy, networkAvailable))
	provide(LiveTvPlaybackResetService(liveTvPlaybackPolicy))

	val playSessionService = PlaySessionService(api)
	provide(playSessionService)
	provide(PlaySessionSocketService(api, playSessionService, lifecycle))

	provide(LyricsPlayerService(api))

	if (mediaSegmentSkipTypes.isNotEmpty()) provide(MediaSegmentService(api, mediaSegmentSkipTypes))
}
