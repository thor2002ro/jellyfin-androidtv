package org.jellyfin.androidtv.test

import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue

enum class PlaybackCapabilityCause(
	val property: ProfileConditionValue?,
	val reason: String,
	val codecType: CodecType,
) {
	VIDEO_LEVEL(ProfileConditionValue.VIDEO_LEVEL, "VideoLevelNotSupported", CodecType.VIDEO),
	VIDEO_RESOLUTION(ProfileConditionValue.WIDTH, "VideoResolutionNotSupported", CodecType.VIDEO),
	VIDEO_BIT_DEPTH(ProfileConditionValue.VIDEO_BIT_DEPTH, "VideoBitDepthNotSupported", CodecType.VIDEO),
	VIDEO_FRAMERATE(ProfileConditionValue.VIDEO_FRAMERATE, "VideoFramerateNotSupported", CodecType.VIDEO),
	REF_FRAMES(ProfileConditionValue.REF_FRAMES, "RefFramesNotSupported", CodecType.VIDEO),
	INTERLACED_VIDEO(null, "InterlacedVideoNotSupported", CodecType.VIDEO),
	ANAMORPHIC_VIDEO(ProfileConditionValue.IS_ANAMORPHIC, "AnamorphicVideoNotSupported", CodecType.VIDEO),
	VIDEO_RANGE(ProfileConditionValue.VIDEO_RANGE_TYPE, "VideoRangeTypeNotSupported", CodecType.VIDEO),
	VIDEO_BITRATE(ProfileConditionValue.VIDEO_BITRATE, "VideoBitrateNotSupported", CodecType.VIDEO),
	AUDIO_CHANNELS(ProfileConditionValue.AUDIO_CHANNELS, "AudioChannelsNotSupported", CodecType.AUDIO),
	AUDIO_PROFILE(ProfileConditionValue.AUDIO_PROFILE, "AudioProfileNotSupported", CodecType.AUDIO),
	AUDIO_SAMPLE_RATE(null, "AudioSampleRateNotSupported", CodecType.AUDIO),
	AUDIO_BIT_DEPTH(null, "AudioBitDepthNotSupported", CodecType.AUDIO),
	AUDIO_BITRATE(ProfileConditionValue.AUDIO_BITRATE, "AudioBitrateNotSupported", CodecType.AUDIO),
	STREAM_COUNT(ProfileConditionValue.NUM_AUDIO_STREAMS, "StreamCountExceedsLimit", CodecType.VIDEO),
	CONTAINER_BITRATE(null, "ContainerBitrateExceedsLimit", CodecType.VIDEO),
}

data class PlaybackCapabilityCase(
	val id: String,
	val descriptorId: String,
	val cause: PlaybackCapabilityCause,
	val value: String,
	val codec: String,
)

object PlaybackCapabilityMatrix {
	val unsupportedReasons = PlaybackCapabilityCause.entries
		.filter { it.property == null && it != PlaybackCapabilityCause.CONTAINER_BITRATE }
		.mapTo(sortedSetOf(), PlaybackCapabilityCause::reason)

	fun plan(items: Collection<PlaybackMediaDescriptor>): List<PlaybackCapabilityCase> = PlaybackCapabilityCause.entries.mapNotNull { cause ->
		items.asSequence().sortedBy(PlaybackMediaDescriptor::id).mapNotNull { item -> item.caseFor(cause) }.firstOrNull()
	}
}

fun PlaybackCapabilityCase.configure(production: DeviceProfile, sourceContainer: String?): ConfiguredPlaybackProfile? {
	if (cause == PlaybackCapabilityCause.CONTAINER_BITRATE) {
		return ConfiguredPlaybackProfile(
			profile = production.copy(maxStreamingBitrate = value.toInt() - 1),
			expectedMethods = setOf(org.jellyfin.playback.core.mediastream.MediaConversionMethod.Transcode),
		)
	}
	val property = cause.property ?: return null
	val container = sourceContainer?.takeIf(String::isNotBlank) ?: return null
	val advertised = production.directPlayProfiles.any { directPlay ->
		directPlay.type == DlnaProfileType.VIDEO && directPlay.container.declares(container) &&
			(if (cause.codecType == CodecType.VIDEO) directPlay.videoCodec else directPlay.audioCodec).declares(codec)
	}
	val declaration = if (advertised) emptyList() else listOf(
		DirectPlayProfile(
			container = container,
			audioCodec = if (cause.codecType == CodecType.AUDIO) codec else null,
			videoCodec = if (cause.codecType == CodecType.VIDEO) codec else null,
			type = DlnaProfileType.VIDEO,
		)
	)
	return ConfiguredPlaybackProfile(
		profile = production.copy(
			directPlayProfiles = production.directPlayProfiles + declaration,
			codecProfiles = production.codecProfiles + CodecProfile(
				type = cause.codecType,
				conditions = listOf(
					ProfileCondition(
						condition = ProfileConditionType.NOT_EQUALS,
						property = property,
						value = value,
						isRequired = true,
					)
				),
				applyConditions = emptyList(),
				codec = codec,
			),
		),
		expectedMethods = setOf(org.jellyfin.playback.core.mediastream.MediaConversionMethod.Transcode),
	)
}

private fun PlaybackMediaDescriptor.caseFor(cause: PlaybackCapabilityCause): PlaybackCapabilityCase? {
	val audio = audioStreams.firstOrNull()
	val value = when (cause) {
		PlaybackCapabilityCause.VIDEO_LEVEL -> videoLevel?.toString()
		PlaybackCapabilityCause.VIDEO_RESOLUTION -> width?.toString()
		PlaybackCapabilityCause.VIDEO_BIT_DEPTH -> bitDepth?.toString()
		PlaybackCapabilityCause.VIDEO_FRAMERATE -> videoFrameRate?.toString()
		PlaybackCapabilityCause.REF_FRAMES -> videoRefFrames?.toString()
		PlaybackCapabilityCause.INTERLACED_VIDEO -> videoInterlaced?.toString()
		PlaybackCapabilityCause.ANAMORPHIC_VIDEO -> videoAnamorphic?.toString()
		PlaybackCapabilityCause.VIDEO_RANGE -> videoRange
		PlaybackCapabilityCause.VIDEO_BITRATE -> videoBitrate?.toString()
		PlaybackCapabilityCause.AUDIO_CHANNELS -> audio?.channels?.toString()
		PlaybackCapabilityCause.AUDIO_PROFILE -> audio?.profile
		PlaybackCapabilityCause.AUDIO_SAMPLE_RATE -> audio?.sampleRate?.toString()
		PlaybackCapabilityCause.AUDIO_BIT_DEPTH -> audio?.bitDepth?.toString()
		PlaybackCapabilityCause.AUDIO_BITRATE -> audio?.bitrate?.toString()
		PlaybackCapabilityCause.STREAM_COUNT -> audioStreamCount.takeIf { it > 0 }?.toString()
		PlaybackCapabilityCause.CONTAINER_BITRATE -> containerBitrate?.takeIf { it > 1 }?.toString()
	} ?: return null
	val codec = if (cause.codecType == CodecType.AUDIO) audio?.codec else videoCodec
	return PlaybackCapabilityCase(
		id = "reason-${cause.name.lowercase().replace('_', '-')}",
		descriptorId = id,
		cause = cause,
		value = value,
		codec = codec ?: return null,
	)
}

private fun String?.declares(value: String) = this?.split(',')?.any { it.equals(value, ignoreCase = true) } == true
