package org.jellyfin.playback.jellyfin

import androidx.lifecycle.Lifecycle
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackRecoveryService
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackResetService
import org.jellyfin.playback.jellyfin.lyrics.LyricsPlayerService
import org.jellyfin.playback.jellyfin.mediasegment.MediaSegmentService
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamOptions
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamResolver
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.playback.jellyfin.playsession.PlaySessionSocketService
import org.jellyfin.playback.jellyfin.recovery.DoviPlaybackRecoveryService
import org.jellyfin.playback.jellyfin.recovery.DoviRecoveryHandoffCoordinator
import org.jellyfin.playback.jellyfin.recovery.NetworkPlaybackRecoveryService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaSourceInfo

typealias JellyfinMediaStreamOptionsProvider = (BaseItemDto, String?) -> JellyfinMediaStreamOptions
data class JellyfinDeviceProfileRequest(val profile: DeviceProfile, val requestToken: Long)
typealias JellyfinDeviceProfileProvider = (QueueEntry) -> JellyfinDeviceProfileRequest
typealias JellyfinDoviDecisionValidator = (QueueEntry, Long, MediaSourceInfo?, MediaConversionMethod?, DoviDecision?) -> Unit

fun jellyfinPlugin(
	api: ApiClient,
	deviceProfileBuilder: JellyfinDeviceProfileProvider,
	mediaStreamOptionsProvider: JellyfinMediaStreamOptionsProvider = { _, _ ->
		JellyfinMediaStreamOptions()
	},
	mediaSegmentSkipTypes: Set<MediaSegmentType> = emptySet(),
	lifecycle: Lifecycle? = null,
	liveTvDirectPlayEnabled: () -> Boolean = { true },
	networkAvailable: () -> Boolean = { true },
	doviDecisionValidator: JellyfinDoviDecisionValidator = { _, _, _, _, _ -> },
) = playbackPlugin {
	val liveTvPlaybackPolicy = LiveTvPlaybackPolicy(liveTvDirectPlayEnabled)
	val doviRecoveryHandoff = DoviRecoveryHandoffCoordinator()

	provide(JellyfinMediaStreamResolver(api, deviceProfileBuilder, mediaStreamOptionsProvider, liveTvPlaybackPolicy, doviDecisionValidator))
	provide(DoviPlaybackRecoveryService(doviRecoveryHandoff))
	provide(NetworkPlaybackRecoveryService(liveTvPlaybackPolicy, networkAvailable, doviRecoveryHandoff))
	provide(LiveTvPlaybackRecoveryService(liveTvPlaybackPolicy, networkAvailable, doviRecoveryHandoff))
	provide(LiveTvPlaybackResetService(liveTvPlaybackPolicy))

	val playSessionService = PlaySessionService(api)
	provide(playSessionService)
	provide(PlaySessionSocketService(api, playSessionService, lifecycle))

	provide(LyricsPlayerService(api))

	if (mediaSegmentSkipTypes.isNotEmpty()) provide(MediaSegmentService(api, mediaSegmentSkipTypes))
}
