package org.jellyfin.androidtv.util.profile

import io.github.thor2002ro.libdovi.DoviBridge
import io.github.thor2002ro.libdovi.DoviCapability
import io.github.thor2002ro.libdovi.DoviPresentation
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.playbackPlayerPreferences
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.dovi.DoviCompatibilityPolicy
import org.jellyfin.playback.dovi.DoviDecision
import org.jellyfin.playback.dovi.DoviDecisionReason
import org.jellyfin.playback.dovi.DoviDeviceCapabilities
import org.jellyfin.playback.dovi.DoviPlaybackBackend
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.DoviSource
import org.jellyfin.playback.dovi.DoviSourceLayer
import org.jellyfin.playback.dovi.DoviSourceProfile
import org.jellyfin.playback.dovi.DoviVideoCodec
import org.jellyfin.playback.dovi.DoviWorkarounds
import org.jellyfin.playback.dovi.doviDecision
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

private val recognizedDoviVideoRangeTypes = setOf(
	VideoRangeType.DOVI,
	VideoRangeType.DOVI_WITH_EL,
	VideoRangeType.DOVI_WITH_ELHDR10_PLUS,
	VideoRangeType.DOVI_WITH_HDR10,
	VideoRangeType.DOVI_WITH_HDR10_PLUS,
	VideoRangeType.DOVI_WITH_HLG,
	VideoRangeType.DOVI_WITH_SDR,
)

// Dolby Vision bitstream compatibility identifiers: 1 = HDR10, 4 = HLG.
private const val DOVI_BL_COMPATIBILITY_HDR10 = 1
private const val DOVI_BL_COMPATIBILITY_HLG = 4

internal data class DoviBridgeEvidence(
	val available: Boolean,
	val capabilities: Set<DoviCapability>,
)

internal data class DoviPlaybackRequest(
	val mode: org.jellyfin.playback.dovi.DoviCompatibilityMode,
	val backend: DoviPlaybackBackend,
	val codec: DoviVideoCodec,
	val container: String?,
	val sourceRangeType: VideoRangeType,
	val source: DoviSource,
	val device: DoviDeviceCapabilities,
	val bridge: DoviBridgeEvidence,
	val workarounds: DoviWorkarounds = DoviWorkarounds(),
	val builtInPlayerSelected: Boolean = true,
	val retrySuppressed: Boolean = false,
)

internal data class DoviPlaybackPlan(
	val decision: DoviDecision,
	val mediaSourceId: String,
	val container: String?,
	val codec: DoviVideoCodec,
	val sourceRangeType: VideoRangeType,
) {
	val advertisedHevcRangeTypes: Set<VideoRangeType>
		get() = if (
			codec == DoviVideoCodec.HEVC &&
			sourceRangeType in recognizedDoviVideoRangeTypes &&
			decision.route != DoviRoute.ServerFallback
		) {
			setOf(sourceRangeType)
		} else {
			emptySet()
		}
}

internal fun decideDoviPlayback(request: DoviPlaybackRequest): DoviPlaybackPlan = DoviPlaybackPlan(
	decision = DoviCompatibilityPolicy.decide(
		DoviCompatibilityPolicy.Input(
			mode = request.mode,
			backend = request.backend,
			codec = request.codec,
			container = request.container,
			source = request.source,
			device = request.device,
			bridgeAvailable = request.bridge.available,
			nativeCapabilities = request.bridge.capabilities,
			workarounds = request.workarounds,
			builtInPlayerSelected = request.builtInPlayerSelected,
			retrySuppressed = request.retrySuppressed,
		),
	),
	mediaSourceId = "test-source",
	container = request.container,
	codec = request.codec,
	sourceRangeType = request.sourceRangeType,
)

internal fun QueueEntry.bindDoviPlaybackPlan(plan: DoviPlaybackPlan?) {
	// Clear an earlier source's decision before binding the current request.
	doviDecision = plan?.decision
}

internal fun QueueEntry.retainDoviPlaybackPlanFor(
	plan: DoviPlaybackPlan?,
	mediaSource: MediaSourceInfo,
	expectedDecision: DoviDecision?,
) {
	val video = mediaSource.mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.VIDEO }
	val matches = plan != null &&
		mediaSource.id == plan.mediaSourceId &&
		mediaSource.container.equals(plan.container, ignoreCase = true) &&
		video?.toDoviVideoCodec() == plan.codec &&
		video.videoRangeType == plan.sourceRangeType
	doviDecision = plan?.decision.takeIf { matches && it == expectedDecision }
}

internal fun createDoviPlaybackPlan(
	item: BaseItemDto,
	mediaSourceId: String?,
	userPreferences: UserPreferences,
	mediaTest: MediaCodecCapabilitiesTest,
	retrySuppressed: Boolean,
	bridge: DoviBridgeEvidence = currentDoviBridgeEvidence(),
	displayHdrTypes: Set<Int>,
): DoviPlaybackPlan? {
	val (mediaSource, videoStream) = item.findDoviVideo(mediaSourceId) ?: return null
	val codec = videoStream.toDoviVideoCodec()
	val source = videoStream.toDoviSource()
	if (codec == DoviVideoCodec.HEVC && videoStream.videoRangeType !in recognizedDoviVideoRangeTypes) {
		return DoviPlaybackPlan(
			decision = DoviDecision(
				route = DoviRoute.ServerFallback,
				reason = DoviDecisionReason.NO_COMPATIBLE_ROUTE,
				invalidStream = videoStream.videoRangeType == VideoRangeType.DOVI_INVALID,
			),
			mediaSourceId = requireNotNull(mediaSource.id),
			container = mediaSource.container,
			codec = codec,
			sourceRangeType = videoStream.videoRangeType,
		)
	}
	val useHdrPlayer = userPreferences[UserPreferences.playbackPlayerPreferences(hdr = true).playbackBackend] != PlaybackBackend.SAME_VIDEO_PLAYER
	val playerPreferences = UserPreferences.playbackPlayerPreferences(useHdrPlayer)
	val externalPlayerSelected = userPreferences[playerPreferences.useExternalPlayer]
	val builtInPlayerSelected = !externalPlayerSelected &&
		userPreferences[playerPreferences.playbackRewriteVideoEnabled]

	return decideDoviPlayback(
		DoviPlaybackRequest(
			mode = userPreferences[UserPreferences.doviCompatibilityMode].mode,
			backend = userPreferences[playerPreferences.playbackBackend].toDoviBackend(),
			codec = codec,
			container = mediaSource.container,
			sourceRangeType = videoStream.videoRangeType,
			source = source,
			device = effectiveDoviDeviceCapabilities(
				mediaTest = mediaTest,
				enabled = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.ENABLE),
				disabled = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.DISABLE),
				displayHdrTypes = displayHdrTypes,
			),
			bridge = bridge,
			builtInPlayerSelected = builtInPlayerSelected,
			retrySuppressed = retrySuppressed,
		),
	).copy(mediaSourceId = requireNotNull(mediaSource.id))
}

private fun currentDoviBridgeEvidence(): DoviBridgeEvidence {
	if (!DoviBridge.isAvailable()) return DoviBridgeEvidence(false, emptySet())
	return runCatching { DoviBridge.capabilities() }
		.fold(
			onSuccess = { capabilities -> DoviBridgeEvidence(true, capabilities) },
			onFailure = { DoviBridgeEvidence(false, emptySet()) },
		)
}

internal fun effectiveDoviDeviceCapabilities(
	mediaTest: MediaCodecCapabilitiesTest,
	enabled: Set<VideoRangeType>,
	disabled: Set<VideoRangeType>,
	displayHdrTypes: Set<Int>,
): DoviDeviceCapabilities {
	val profile5Ranges = setOf(VideoRangeType.DOVI)
	val profile7Ranges = setOf(VideoRangeType.DOVI_WITH_EL, VideoRangeType.DOVI_WITH_ELHDR10_PLUS)
	val profile8Ranges = setOf(
		VideoRangeType.DOVI_WITH_HDR10,
		VideoRangeType.DOVI_WITH_HDR10_PLUS,
		VideoRangeType.DOVI_WITH_HLG,
		VideoRangeType.DOVI_WITH_SDR,
	)

	fun effective(ranges: Set<VideoRangeType>, detected: Boolean): Boolean = when {
		ranges.any(disabled::contains) -> false
		ranges.any(enabled::contains) -> true
		else -> detected
	}

	val reportedProfile7 = mediaTest.supportsHevcDolbyVisionProfile7()
	val knownProfile7 = KnownDefects.unreportedDoviProfile7Support &&
		mediaTest.supportsHevcDolbyVision() &&
		mediaTest.supportsHevcMain10() &&
		mediaTest.supportsHevcHDR10()
	val supportsProfile7 = effective(profile7Ranges, reportedProfile7 || knownProfile7)
	val supportsHlgDecoder = effective(setOf(VideoRangeType.HLG), mediaTest.supportsHevcHlg()) &&
		DISPLAY_HDR_TYPE_HLG in displayHdrTypes
	// Main10 decoding is enough to attempt a base presentation: Android TV devices may
	// tone-map it for an SDR display, and playback recovery handles decoder rejection.
	val canAttemptHdrBase = mediaTest.supportsHevcMain10()
	val supportsHdr10Base = effective(
		setOf(VideoRangeType.HDR10),
		canAttemptHdrBase || DISPLAY_HDR_TYPE_HDR10 in displayHdrTypes,
	)
	val supportsHdr10PlusBase = effective(
		setOf(VideoRangeType.HDR10_PLUS),
		canAttemptHdrBase || DISPLAY_HDR_TYPE_HDR10_PLUS in displayHdrTypes,
	)
	val supportsHlgBase = effective(
		setOf(VideoRangeType.HLG),
		canAttemptHdrBase || DISPLAY_HDR_TYPE_HLG in displayHdrTypes,
	)
	val supportsProfile8 = effective(profile8Ranges, mediaTest.supportsHevcDolbyVisionProfile8())

	return DoviDeviceCapabilities(
		supportsProfile5 = effective(profile5Ranges, mediaTest.supportsHevcDolbyVisionProfile5()),
		supportsProfile7 = supportsProfile7,
		supportsProfile8 = supportsProfile8,
		supportsProfile84 = supportsProfile8 && supportsHlgDecoder,
		// Android reports Profile 7 decoding, not MEL/FEL processing separately.
		supportsMel = false,
		supportsFel = false,
		supportsHdr10 = supportsHdr10Base,
		supportsHdr10Plus = supportsHdr10PlusBase,
		supportsHlg = supportsHlgBase,
	)
}

internal fun BaseItemDto.findDoviVideo(mediaSourceId: String?): Pair<MediaSourceInfo, MediaStream>? {
	val sources = mediaSources.orEmpty()
	val source = if (mediaSourceId == null) {
		sources.singleOrNull()
	} else {
		sources.firstOrNull { it.id == mediaSourceId }
	} ?: return null
	if (source.id == null) return null
	val video = source.mediaStreams.orEmpty().firstOrNull { stream ->
		stream.type == MediaStreamType.VIDEO && stream.isDolbyVision()
	} ?: return null
	return source to video
}

private fun MediaStream.isDolbyVision(): Boolean =
	dvProfile != null || videoRangeType in recognizedDoviVideoRangeTypes ||
		videoRangeType == VideoRangeType.DOVI_INVALID

private fun MediaStream.toDoviVideoCodec(): DoviVideoCodec = when (codec?.lowercase()) {
	"hevc", "h265", "h.265" -> DoviVideoCodec.HEVC
	"av1" -> DoviVideoCodec.AV1
	else -> DoviVideoCodec.OTHER
}

internal fun MediaStream.toDoviSource(): DoviSource {
	val profile = when (dvProfile) {
		5 -> DoviSourceProfile.PROFILE_5
		7 -> DoviSourceProfile.PROFILE_7
		8 -> if (videoRangeType == VideoRangeType.DOVI_WITH_HLG) {
			DoviSourceProfile.PROFILE_8_4
		} else {
			DoviSourceProfile.PROFILE_8_1
		}
		else -> when (videoRangeType) {
			VideoRangeType.DOVI -> DoviSourceProfile.PROFILE_5
			VideoRangeType.DOVI_WITH_EL,
			VideoRangeType.DOVI_WITH_ELHDR10_PLUS,
			-> DoviSourceProfile.PROFILE_7
			VideoRangeType.DOVI_WITH_HLG -> DoviSourceProfile.PROFILE_8_4
			VideoRangeType.DOVI_WITH_HDR10,
			VideoRangeType.DOVI_WITH_HDR10_PLUS,
			VideoRangeType.DOVI_WITH_SDR,
			-> DoviSourceProfile.PROFILE_8_1
			else -> DoviSourceProfile.UNKNOWN
		}
	}
	val sourceBase = when {
		videoRangeType == VideoRangeType.DOVI_WITH_HDR10_PLUS ||
			videoRangeType == VideoRangeType.DOVI_WITH_ELHDR10_PLUS -> DoviPresentation.HDR10_PLUS
		videoRangeType == VideoRangeType.DOVI_WITH_HLG ||
			dvBlSignalCompatibilityId == DOVI_BL_COMPATIBILITY_HLG -> DoviPresentation.HLG
		videoRangeType == VideoRangeType.DOVI_WITH_HDR10 ||
			dvBlSignalCompatibilityId == DOVI_BL_COMPATIBILITY_HDR10 -> DoviPresentation.HDR10
		profile == DoviSourceProfile.PROFILE_7 -> DoviPresentation.HDR10
		else -> DoviPresentation.UNKNOWN
	}
	return DoviSource(
		profile = profile,
		layer = if (profile == DoviSourceProfile.PROFILE_7) DoviSourceLayer.UNKNOWN else DoviSourceLayer.NONE,
		sourceBasePresentation = sourceBase,
		dvLevel = dvLevel,
		// Encoded samples are not available while building the playback-info request.
		inspection = null,
	)
}

private fun PlaybackBackend.toDoviBackend(): DoviPlaybackBackend = when (this) {
	PlaybackBackend.SAME_VIDEO_PLAYER,
	PlaybackBackend.EXOPLAYER -> DoviPlaybackBackend.MEDIA3
	PlaybackBackend.MPV -> DoviPlaybackBackend.MPV
	PlaybackBackend.LIBVLC -> DoviPlaybackBackend.OTHER
}
