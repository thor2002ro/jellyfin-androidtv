package org.jellyfin.androidtv.util.profile

import android.content.Context
import android.media.AudioFormat
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import kotlin.math.roundToInt
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.AudioBehavior
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.constant.BitstreamAudioMode
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.preference.constant.PlaybackResolution
import org.jellyfin.androidtv.preference.isAudioPassthroughEnabled
import org.jellyfin.androidtv.preference.playbackBackend
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegVideo
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.DoviVideoCodec
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.deviceprofile.DeviceProfileBuilder
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

private const val VIDEO_BIT_DEPTH_8 = 8
private const val VIDEO_BIT_DEPTH_10 = 10

private val downmixSupportedAudioCodecs = arrayOf(
	Codec.Audio.AAC,
	Codec.Audio.MP2,
	Codec.Audio.MP3,
)

private val supportedAudioCodecs = arrayOf(
	Codec.Audio.AAC,
	Codec.Audio.AAC_LATM,
	Codec.Audio.AC3,
	Codec.Audio.AC4,
	Codec.Audio.ALAC,
	Codec.Audio.DCA,
	Codec.Audio.DTS,
	Codec.Audio.EAC3,
	Codec.Audio.FLAC,
	Codec.Audio.MLP,
	Codec.Audio.MP2,
	Codec.Audio.MP3,
	Codec.Audio.OPUS,
	Codec.Audio.PCM_ALAW,
	Codec.Audio.PCM_MULAW,
	Codec.Audio.PCM_S16LE,
	Codec.Audio.PCM_S20LE,
	Codec.Audio.PCM_S24LE,
	Codec.Audio.TRUEHD,
	Codec.Audio.VORBIS,
)

private val hlsMpegTsAudioCodecs = arrayOf(
	Codec.Audio.AAC,
	Codec.Audio.AC3,
	Codec.Audio.EAC3,
	Codec.Audio.MP3
)

private val hlsFmp4AudioCodecs = arrayOf(
	Codec.Audio.AAC,
	Codec.Audio.AC3,
	Codec.Audio.EAC3,
	Codec.Audio.MP3,
	Codec.Audio.ALAC,
	Codec.Audio.FLAC,
	Codec.Audio.OPUS,
	Codec.Audio.DTS,
)

private val eac3ServerAudioCodecs = setOf(Codec.Audio.EAC3)
private val dtsServerAudioCodecs = setOf(Codec.Audio.DCA, Codec.Audio.DTS)

private val passthroughAudioCodecMimes = mapOf(
	MimeTypes.AUDIO_AC3 to setOf(Codec.Audio.AC3),
	MimeTypes.AUDIO_AC4 to setOf(Codec.Audio.AC4),
	MimeTypes.AUDIO_E_AC3 to eac3ServerAudioCodecs,
	MimeTypes.AUDIO_E_AC3_JOC to eac3ServerAudioCodecs,
	MimeTypes.AUDIO_DTS to dtsServerAudioCodecs,
	MimeTypes.AUDIO_DTS_EXPRESS to dtsServerAudioCodecs,
	MimeTypes.AUDIO_DTS_HD to dtsServerAudioCodecs,
	MimeTypes.AUDIO_TRUEHD to setOf(Codec.Audio.MLP, Codec.Audio.TRUEHD),
)

private val allPassthroughAudioCodecs = passthroughAudioCodecMimes.values.flatten().toSet()

private fun UserPreferences.getMaxBitrate(): Int {
	var maxBitrate = this[UserPreferences.maxBitrate].toFloatOrNull()

	// The value "0" was used in an older release, make sure we prevent that from being used to avoid video not playing
	if (maxBitrate == null || maxBitrate < 0.01f) maxBitrate = UserPreferences.maxBitrate.defaultValue.toFloat()

	// Convert megabit to bit
	return (maxBitrate * 1_000_000).roundToInt()
}

private fun isAudioCodecAvailable(codec: String, supportsOpus: Boolean): Boolean = when (codec) {
	Codec.Audio.OPUS -> supportsOpus
	else -> true
}

@JvmOverloads
internal fun createDeviceProfile(
	context: Context,
	userPreferences: UserPreferences,
	serverVersion: ServerVersion,
	enableFfmpegAudio: Boolean = when (userPreferences[UserPreferences.playbackBackend]) {
		PlaybackBackend.EXOPLAYER, PlaybackBackend.SAME_VIDEO_PLAYER -> true
		else -> false
	},
	enableMpvAudio: Boolean = userPreferences[UserPreferences.playbackBackend] == PlaybackBackend.MPV,
	enableFfmpegVideo: Boolean = enableFfmpegAudio && userPreferences[UserPreferences.preferExoPlayerFfmpegVideo],
	doviPlaybackPlan: DoviPlaybackPlan? = null,
	softwareCodecsEnabled: Boolean = userPreferences[UserPreferences.softwareCodecsEnabled],
	mediaTest: MediaCodecCapabilitiesTest = MediaCodecCapabilitiesTest(softwareCodecsEnabled),
): DeviceProfile {
	return createDeviceProfile(
		mediaTest = mediaTest,
		maxBitrate = userPreferences.getMaxBitrate(),
		maxResolution = userPreferences[UserPreferences.maxResolution],
		isAC3PrefEnabled = userPreferences.isAudioPassthroughEnabled(BitstreamAudioFormat.AC3.mimeType),
		isEAC3PrefEnabled = userPreferences.isAudioPassthroughEnabled(BitstreamAudioFormat.EAC3.mimeType),
		isDTSPrefEnabled = userPreferences.isAudioPassthroughEnabled(BitstreamAudioFormat.DTS.mimeType),
		isTrueHDPrefEnabled = userPreferences.isAudioPassthroughEnabled(BitstreamAudioFormat.TRUEHD.mimeType),
		downMixAudio = userPreferences[UserPreferences.audioBehaviour] == AudioBehavior.DOWNMIX_TO_STEREO,
		assDirectPlay = userPreferences[UserPreferences.assDirectPlay],
		pgsDirectPlay = userPreferences[UserPreferences.pgsDirectPlay],
		userAVCLevel = userPreferences[UserPreferences.userAVCLevel].level,
		userHEVCLevel = userPreferences[UserPreferences.userHEVCLevel].level,
		forceEnabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.ENABLE),
		forceDisabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.DISABLE),
		doviPlaybackPlan = doviPlaybackPlan,
		passthroughAudioCodecs = userPreferences.profilePassthroughAudioCodecs(
			getSupportedPassthroughAudioMimes(context, passthroughAudioCodecMimes.keys)
		),
		enableFfmpegAudio = enableFfmpegAudio,
		enableMpvAudio = enableMpvAudio,
		enableFfmpegVideo = enableFfmpegVideo,
	)
}

@OptIn(UnstableApi::class)
internal fun createDeviceProfile(
	mediaTest: MediaCodecCapabilitiesTest,
	maxBitrate: Int,
	maxResolution: PlaybackResolution = PlaybackResolution.NATIVE,
	isAC3PrefEnabled: Boolean,
	isEAC3PrefEnabled: Boolean,
	isDTSPrefEnabled: Boolean,
	isTrueHDPrefEnabled: Boolean,
	downMixAudio: Boolean,
	assDirectPlay: Boolean,
	pgsDirectPlay: Boolean,
	userAVCLevel: Int?,
	userHEVCLevel: Int?,
	forceEnabledHdr: Set<VideoRangeType>,
	forceDisabledHdr: Set<VideoRangeType>,
	doviPlaybackPlan: DoviPlaybackPlan? = null,
	passthroughAudioCodecs: Set<String> = allPassthroughAudioCodecs,
	enableFfmpegAudio: Boolean = false,
	enableMpvAudio: Boolean = false,
	enableFfmpegVideo: Boolean = false,
) = buildDeviceProfile {
	val canMixAudioLocally = enableFfmpegAudio || enableMpvAudio
	val supportsOpus = mediaTest.supportsOpus() || enableMpvAudio ||
		(enableFfmpegAudio && FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_OPUS))
	// Media3 keeps FFmpeg audio decoding enabled even when passthrough or software video decoding is disabled.
	val locallyDecodablePassthroughAudioCodecs = passthroughAudioCodecMimes.entries
		.filter { (mime) ->
			enableMpvAudio || mediaTest.supportsMimeType(mime) ||
				(enableFfmpegAudio && FfmpegLibrary.supportsFormat(mime))
		}
		.flatMapTo(mutableSetOf()) { (_, codecs) -> codecs }
	val enabledPassthroughCodecs = enabledPassthroughAudioCodecs(
		isAC3Enabled = isAC3PrefEnabled,
		isEAC3Enabled = isEAC3PrefEnabled,
		isDTSEnabled = isDTSPrefEnabled,
		isTrueHDEnabled = isTrueHDPrefEnabled,
	)
	val allowedAudioCodecs = when {
		// Media3 and MPV mix decoded PCM locally; other backends retain the server stereo policy.
		downMixAudio && !canMixAudioLocally -> downmixSupportedAudioCodecs
		else -> supportedAudioCodecs.filterNot { audioCodec ->
			val isPassthroughCodec = audioCodec in allPassthroughAudioCodecs
			val canDecodeLocally = audioCodec in locallyDecodablePassthroughAudioCodecs
			val canPassthrough = !downMixAudio && audioCodec in passthroughAudioCodecs && audioCodec in enabledPassthroughCodecs
			!isAudioCodecAvailable(audioCodec, supportsOpus) ||
				(isPassthroughCodec && !canDecodeLocally && !canPassthrough)
		}.toTypedArray()
	}

	val supportsHevc = mediaTest.supportsHevc()
	val supportsHevcMain10 = mediaTest.supportsHevcMain10()
	val attemptSelectedHevcSource =
		doviPlaybackPlan?.codec == DoviVideoCodec.HEVC &&
			doviPlaybackPlan.decision.route != DoviRoute.ServerFallback
	val advertiseHevcMain10 = supportsHevcMain10 || attemptSelectedHevcSource
	val hevcMainLevel = userHEVCLevel ?: mediaTest.getHevcMainLevel()
	val hevcMain10Level = userHEVCLevel ?: mediaTest.getHevcMain10Level()
	val supportsAVC = mediaTest.supportsAVC()
	val supportsAVCHigh10 = mediaTest.supportsAVCHigh10()
	val avcMainLevel = userAVCLevel ?: mediaTest.getAVCMainLevel()
	val avcHigh10Level = userAVCLevel ?: mediaTest.getAVCHigh10Level()
	val supportsAV1 = mediaTest.supportsAV1()
	val supportsAV1Main10 = mediaTest.supportsAV1Main10()
	val supportsVC1 = mediaTest.supportsVc1()
	val supportsMpeg2 = mediaTest.supportsMpeg2()
	val supportsMpeg4Asp = mediaTest.supportsMpeg4Asp()
	val supportsMpeg4Simple = mediaTest.supportsMpeg4Simple()
	val supportsVP8 = mediaTest.supportsVp8()
	val supportsVP9 = mediaTest.supportsVp9()
	val supportsVP9Main8 = mediaTest.supportsVp9Main8()
	val supportsVP9Main10 = mediaTest.supportsVp9Main10()
	val supportsVP9HDR = mediaTest.supportsVp9HDR()
	val supportsVP9HDR10Plus = mediaTest.supportsVp9HDR10Plus()
	val maxResolutionAVC = mediaTest.getMaxResolution(MimeTypes.VIDEO_H264).capTo(maxResolution)
	val maxResolutionHevc = mediaTest.getMaxResolution(MimeTypes.VIDEO_H265).capTo(maxResolution)
	val maxHevcWidth = if (attemptSelectedHevcSource) maxResolution.maxWidth else maxResolutionHevc.width
	val maxHevcHeight = if (attemptSelectedHevcSource) maxResolution.maxHeight else maxResolutionHevc.height
	val maxResolutionAV1 = mediaTest.getMaxResolution(MimeTypes.VIDEO_AV1).capTo(maxResolution)
	val maxResolutionVC1 = mediaTest.getMaxResolution(MimeTypes.VIDEO_VC1).capTo(maxResolution)
	val maxResolutionVP9 = mediaTest.getMaxResolution(MimeTypes.VIDEO_VP9).capTo(maxResolution)

	/// HDR capabilities

	// Codecs
	// AV1
	val supportsAV1DolbyVision = mediaTest.supportsAV1DolbyVision()
	val supportsAV1HDR10 = mediaTest.supportsAV1HDR10()
	val supportsAV1HDR10Plus = mediaTest.supportsAV1HDR10Plus()

	name = "AndroidTV-Default"

	/// Bitrate
	maxStaticBitrate = maxBitrate
	maxStreamingBitrate = maxBitrate

	/// Transcoding profiles
	// Video
	val hlsMpegTsVideoCodecs = listOfNotNull(
		if (supportsHevc) Codec.Video.HEVC else null,
		Codec.Video.H264
	).toTypedArray()
	// Server 12 also uses this list to match video stream-copy candidates. Keep the practical encoder
	// codecs first, then append codecs that this device can decode from fMP4 segments.
	val hlsFmp4VideoCodecs = hlsMpegTsVideoCodecs + listOfNotNull(
		if (supportsAV1) Codec.Video.AV1 else null,
		if (supportsVP9) Codec.Video.VP9 else null,
	)

	transcodingProfile {
		type = DlnaProfileType.VIDEO
		context = EncodingContext.STREAMING

		container = Codec.Container.TS
		protocol = MediaStreamProtocol.HLS

		videoCodec(*hlsMpegTsVideoCodecs)
		audioCodec(*hlsMpegTsAudioCodecs.filter(allowedAudioCodecs::contains).toTypedArray())

		copyTimestamps = false
		enableSubtitlesInManifest = true
	}

	transcodingProfile {
		type = DlnaProfileType.VIDEO
		context = EncodingContext.STREAMING

		container = Codec.Container.MP4
		protocol = MediaStreamProtocol.HLS

		videoCodec(*hlsFmp4VideoCodecs)
		audioCodec(*hlsFmp4AudioCodecs.filter(allowedAudioCodecs::contains).toTypedArray())

		copyTimestamps = false
		enableSubtitlesInManifest = true
	}

	// Audio
	transcodingProfile {
		type = DlnaProfileType.AUDIO
		context = EncodingContext.STREAMING

		container = Codec.Container.MP4
		protocol = MediaStreamProtocol.HLS

		audioCodec(Codec.Audio.AAC)
	}

	/// Direct play profiles
	// Video
	directPlayProfile {
		type = DlnaProfileType.VIDEO

		container(
			Codec.Container.AVI,
			Codec.Container.FLV,
			Codec.Container.HLS,
			Codec.Container.M4V,
			Codec.Container.MKV,
			Codec.Container.MOV,
			Codec.Container.MP4,
			Codec.Container.MPEG,
			Codec.Container.TS,
			Codec.Container.WEBM,
		)

		videoCodec(
			Codec.Video.AV1,
			Codec.Video.H264,
			Codec.Video.HEVC,
			Codec.Video.MPEG1VIDEO,
			Codec.Video.MPEG2VIDEO,
			Codec.Video.MPEG4,
			Codec.Video.VC1,
			Codec.Video.VP8,
			Codec.Video.VP9,
		)

		audioCodec(*allowedAudioCodecs)
	}

	// Audio
	// An empty container declaration means every container to the server, including formats Media3 cannot demux.
	directPlayProfile {
		type = DlnaProfileType.AUDIO

		container(
			Codec.Container.AAC,
			Codec.Container.AC3,
			Codec.Container.AC4,
			Codec.Container.EAC3,
			Codec.Container.FLAC,
			Codec.Container.FLV,
			Codec.Container.HLS,
			Codec.Container.M4A,
			Codec.Container.MKV,
			Codec.Container.MOV,
			Codec.Container.MP3,
			Codec.Container.MP4,
			Codec.Container.OGG,
			Codec.Container.TS,
			Codec.Container.WAV,
			Codec.Container.WEBM,
		)

		audioCodec(*allowedAudioCodecs)
	}
	// Server 12 selects the fMP4 remux container from an exact, single-codec MP4 profile.
	for (audioCodec in hlsFmp4AudioCodecs.filter(allowedAudioCodecs::contains)) {
		directPlayProfile {
			type = DlnaProfileType.AUDIO
			container(Codec.Container.MP4)
			audioCodec(audioCodec)
		}
	}

	/// Codec profiles
	// H264 profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.H264

		conditions {
			when {
				!supportsAVC -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE inCollection listOfNotNull(
					"high",
					"main",
					"baseline",
					"constrained baseline",
					if (supportsAVCHigh10) "high 10" else null
				)
			}
		}
	}
	if (supportsAVC) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.H264

			conditions {
				ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals avcMainLevel
			}

			applyConditions {
				ProfileConditionValue.VIDEO_PROFILE inCollection listOf(
					"high",
					"main",
					"baseline",
					"constrained baseline"
				)
			}
		}
	}
	if (supportsAVCHigh10) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.H264

			conditions {
				ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals avcHigh10Level
			}

			applyConditions {
				ProfileConditionValue.VIDEO_PROFILE equals "high 10"
			}
		}
	}

	// H264 ref frames profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.H264

		conditions {
			ProfileConditionValue.REF_FRAMES lowerThanOrEquals 12
		}

		applyConditions {
			ProfileConditionValue.WIDTH greaterThanOrEquals 1200
		}
	}

	// H264 ref frames profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.H264

		conditions {
			ProfileConditionValue.REF_FRAMES lowerThanOrEquals 4
		}

		applyConditions {
			ProfileConditionValue.WIDTH greaterThanOrEquals 1900
		}
	}

	// HEVC profiles
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.HEVC

		conditions {
			when {
				advertiseHevcMain10 -> ProfileConditionValue.VIDEO_PROFILE inCollection listOfNotNull(
					if (supportsHevc) "main" else null,
					"main 10",
				)
				!supportsHevc -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE equals "main"
			}
		}
	}
	if (supportsHevc) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.HEVC

			conditions {
				ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals hevcMainLevel
			}

			applyConditions {
				ProfileConditionValue.VIDEO_PROFILE equals "main"
			}
		}
	}
	if (supportsHevcMain10) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.HEVC

			conditions {
				ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals hevcMain10Level
			}

			applyConditions {
				ProfileConditionValue.VIDEO_PROFILE equals "main 10"
			}
		}
	}

	// AV1 profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.AV1

		conditions {
			when {
				!supportsAV1 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE equals "main"
			}
			if (supportsAV1) {
				ProfileConditionValue.VIDEO_BIT_DEPTH lowerThanOrEquals if (supportsAV1Main10) {
					VIDEO_BIT_DEPTH_10
				} else {
					VIDEO_BIT_DEPTH_8
				}
			}
		}
	}

	// VC1 profile
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VC1

		conditions {
			when {
				!supportsVC1 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
			}
		}
	}

	// MPEG-1 and MPEG-2 share the same Android decoder MIME type
	for (videoCodec in arrayOf(Codec.Video.MPEG1VIDEO, Codec.Video.MPEG2VIDEO)) {
		codecProfile {
			type = CodecType.VIDEO
			codec = videoCodec

			conditions {
				when {
					!supportsMpeg2 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
					else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
				}
			}
		}
	}

	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.MPEG4
		conditions {
			val profiles = listOfNotNull(
				if (supportsMpeg4Simple) "Simple Profile" else null,
				if (supportsMpeg4Asp) "Advanced Simple Profile" else null,
			)
			if (profiles.isEmpty()) ProfileConditionValue.VIDEO_PROFILE equals "none"
			else ProfileConditionValue.VIDEO_PROFILE inCollection profiles
		}
	}

	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VP8
		conditions {
			when {
				!supportsVP8 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
			}
		}
	}

	// VP9 profiles 0 and 2 are the 4:2:0 profiles used by Android video decoders.
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VP9

		conditions {
			when {
				!supportsVP9 -> ProfileConditionValue.VIDEO_PROFILE equals "none"
				supportsVP9Main10 -> ProfileConditionValue.VIDEO_PROFILE inCollection listOfNotNull(
					if (supportsVP9Main8) "profile 0" else null,
					"profile 2",
				)
				else -> ProfileConditionValue.VIDEO_PROFILE equals "profile 0"
			}
			if (supportsVP9) {
				ProfileConditionValue.VIDEO_BIT_DEPTH lowerThanOrEquals if (supportsVP9Main10) {
					VIDEO_BIT_DEPTH_10
				} else {
					VIDEO_BIT_DEPTH_8
				}
			}
		}
	}

	// Get max resolutions for common codecs
	// AVC
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.H264

		conditions {
			ProfileConditionValue.WIDTH lowerThanOrEquals maxResolutionAVC.width
			ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolutionAVC.height
		}
	}

	// HEVC
	if (maxHevcWidth != null && maxHevcHeight != null) {
		codecProfile {
			type = CodecType.VIDEO
			codec = Codec.Video.HEVC

			conditions {
				ProfileConditionValue.WIDTH lowerThanOrEquals maxHevcWidth
				ProfileConditionValue.HEIGHT lowerThanOrEquals maxHevcHeight
			}
		}
	}

	// AV1
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.AV1

		conditions {
			ProfileConditionValue.WIDTH lowerThanOrEquals maxResolutionAV1.width
			ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolutionAV1.height
		}
	}

	// VC1
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VC1

		conditions {
			ProfileConditionValue.WIDTH lowerThanOrEquals maxResolutionVC1.width
			ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolutionVC1.height
		}
	}

	// VP9
	codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.VP9

		conditions {
			ProfileConditionValue.WIDTH lowerThanOrEquals maxResolutionVP9.width
			ProfileConditionValue.HEIGHT lowerThanOrEquals maxResolutionVP9.height
		}
	}

	/// HDR exclude list

	val unsupportedRangeTypesAv1 = buildSet {
		add(VideoRangeType.DOVI_INVALID)

		if (!supportsAV1DolbyVision) {
			add(VideoRangeType.DOVI)
			if (!supportsAV1HDR10) add(VideoRangeType.DOVI_WITH_HDR10)
			if (!supportsAV1HDR10Plus) add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
		}

		if (!supportsAV1HDR10Plus) add(VideoRangeType.HDR10_PLUS)
		if (!supportsAV1HDR10) add(VideoRangeType.HDR10)
	} - forceEnabledHdr + forceDisabledHdr
	val unsupportedRangeTypesVP9 = buildSet {
		if (!supportsVP9HDR10Plus) add(VideoRangeType.HDR10_PLUS)
		if (!supportsVP9HDR) add(VideoRangeType.HDR10)
	} - forceEnabledHdr + forceDisabledHdr

	val baselineUnsupportedRangeTypesHevc = getUnsupportedHevcVideoRangeWorkarounds(
		mediaTest = mediaTest,
		forceEnabledHdr = forceEnabledHdr,
		forceDisabledHdr = forceDisabledHdr,
	).keys
	val unsupportedRangeTypesHevc = when {
		doviPlaybackPlan?.codec != DoviVideoCodec.HEVC -> baselineUnsupportedRangeTypesHevc
		doviPlaybackPlan.decision.route == DoviRoute.ServerFallback ->
			baselineUnsupportedRangeTypesHevc + doviPlaybackPlan.sourceRangeType
		else -> when (doviPlaybackPlan.decision.route) {
		DoviRoute.Native,
		is DoviRoute.SourceBase,
		is DoviRoute.Transform,
		-> baselineUnsupportedRangeTypesHevc -
			doviPlaybackPlan.advertisedHevcRangeTypes
		DoviRoute.ServerFallback -> error("Handled above")
		}
	}

	for ((videoCodec, unsupported) in listOf(
		Codec.Video.AV1 to unsupportedRangeTypesAv1,
		Codec.Video.VP9 to unsupportedRangeTypesVP9,
		Codec.Video.HEVC to unsupportedRangeTypesHevc,
	)) {
		// HDR10Plus can play as HDR10 without dynamic metadata. Only an explicit
		// user disable should prevent this fallback when HDR10 is supported.
		val excludedRanges = if (VideoRangeType.HDR10 !in unsupported && VideoRangeType.HDR10_PLUS !in forceDisabledHdr) {
			unsupported - VideoRangeType.HDR10_PLUS
		} else {
			unsupported
		}
		addUnsupportedVideoRanges(videoCodec, excludedRanges)
	}

	// Audio channel profile
	if (downMixAudio && canMixAudioLocally) codecProfile {
		type = CodecType.AUDIO
		conditions {
			ProfileConditionValue.AUDIO_CHANNELS lowerThanOrEquals 8
		}
	}
	codecProfile {
		type = CodecType.VIDEO_AUDIO

		conditions {
			ProfileConditionValue.AUDIO_CHANNELS lowerThanOrEquals if (downMixAudio && !canMixAudioLocally) 2 else 8
		}
	}

	/// Subtitle profiles
	// Jellyfin server only supports WebVTT subtitles in HLS, other text subtitles will be converted to WebVTT
	// which we do not want so only allow delivery over HLS for WebVTT subtitles
	subtitleProfile(Codec.Subtitle.VTT, embedded = true, hls = true, external = true)
	subtitleProfile(Codec.Subtitle.WEBVTT, embedded = true, hls = true, external = true)

	subtitleProfile(Codec.Subtitle.SRT, embedded = true, external = true)
	subtitleProfile(Codec.Subtitle.SUBRIP, embedded = true, external = true)
	subtitleProfile(Codec.Subtitle.TTML, embedded = true, external = true)

	// Not all subtitles can be loaded standalone by the player
	subtitleProfile(Codec.Subtitle.DVBSUB, embedded = true, encode = true)
	subtitleProfile(Codec.Subtitle.DVDSUB, embedded = true, encode = true)
	subtitleProfile(Codec.Subtitle.IDX, embedded = true, encode = true)
	subtitleProfile(Codec.Subtitle.PGS, embedded = pgsDirectPlay, encode = true)
	subtitleProfile(Codec.Subtitle.PGSSUB, embedded = pgsDirectPlay, encode = true)

	// ASS/SSA is supported via libass extension
	subtitleProfile(Codec.Subtitle.ASS, encode = true, embedded = assDirectPlay, external = assDirectPlay)
	subtitleProfile(Codec.Subtitle.SSA, encode = true, embedded = assDirectPlay, external = assDirectPlay)
}.let { profile ->
	if (enableFfmpegVideo && doviPlaybackPlan == null) profile.withFfmpegVideo(mediaTest, maxResolution, userAVCLevel, userHEVCLevel)
	else profile
}

internal fun DeviceProfileBuilder.addUnsupportedVideoRanges(videoCodec: String, unsupported: Set<VideoRangeType>) {
	// Server 12 also matches HDR10Plus against HDR10. Keep an HDR10-only exclusion
	// separate so its NotEquals condition can still accept supported HDR10Plus.
	val groups = if (VideoRangeType.HDR10 in unsupported && VideoRangeType.HDR10_PLUS !in unsupported) {
		listOf(setOf(VideoRangeType.HDR10), unsupported - VideoRangeType.HDR10)
	} else {
		listOf(unsupported)
	}
	for (ranges in groups.filter { it.isNotEmpty() }) codecProfile {
		type = CodecType.VIDEO
		codec = videoCodec
		conditions {
			// Other sources (notably Dolby Vision) must still exclude unsupported HDR10
			// from their transcode outputs, even though it has a separate input check.
			val excludedOutputs = if (ranges == setOf(VideoRangeType.HDR10)) ranges else unsupported
			// A pipe-delimited NotEquals fails ConditionProcessor, while StreamBuilder
			// splits the same value to exclude output ranges. A trailing separator keeps
			// singleton HDR10Plus from passing through the server's HDR10 fallback;
			// StreamBuilder removes empty entries when constructing the transcode options.
			val suffix = if (excludedOutputs == setOf(VideoRangeType.HDR10_PLUS)) "|" else ""
			ProfileConditionValue.VIDEO_RANGE_TYPE notEquals excludedOutputs.joinToString("|", postfix = suffix) { it.serialName }
		}
		applyConditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE inCollection ranges.map { it.serialName }
		}
	}
}

// Little helper function to more easily define subtitle profiles
private fun DeviceProfileBuilder.subtitleProfile(
	format: String,
	embedded: Boolean = false,
	external: Boolean = false,
	hls: Boolean = false,
	encode: Boolean = false,
) {
	if (embedded) subtitleProfile(format, SubtitleDeliveryMethod.EMBED)
	if (external) subtitleProfile(format, SubtitleDeliveryMethod.EXTERNAL)
	if (hls) subtitleProfile(format, SubtitleDeliveryMethod.HLS)
	if (encode) subtitleProfile(format, SubtitleDeliveryMethod.ENCODE)
}

private fun Size.capTo(resolution: PlaybackResolution) = Size(
	resolution.capWidth(width),
	resolution.capHeight(height),
)

@OptIn(UnstableApi::class)
internal fun UserPreferences.profilePassthroughAudioCodecs(detectedMimes: Set<String>): Set<String> {
	// Enable overrides detection in the server profile; players still validate their output route.
	val forcedMimes = BitstreamAudioFormat.entries
		.filter { this[it.preference] == BitstreamAudioMode.ENABLE && isAudioPassthroughEnabled(it.mimeType) }
		.map { it.mimeType }
	val supportedMimes = detectedMimes + forcedMimes
	return passthroughAudioCodecMimes.entries.flatMapTo(mutableSetOf()) { (mime, codecs) ->
		if (mime in supportedMimes) codecs else emptySet()
	}
}

@OptIn(UnstableApi::class)
fun getSupportedPassthroughAudioMimes(context: Context, mimeTypes: Collection<String>): Set<String> {
	val audioAttributes = passthroughAudioAttributes()
	val audioCapabilities = getAudioCapabilities(context, audioAttributes)
	return mimeTypes.filterTo(mutableSetOf()) { mime ->
		audioCapabilities.supportsPassthrough(mime, audioAttributes)
	}
}

private fun passthroughAudioAttributes() = AudioAttributes.Builder()
	.setUsage(C.USAGE_MEDIA)
	.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
	.build()

@OptIn(UnstableApi::class)
private fun getAudioCapabilities(context: Context, audioAttributes: AudioAttributes) = AudioCapabilities.getCapabilities(
	context,
	audioAttributes,
	null,
	listOf(AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.CHANNEL_OUT_5POINT1),
)

@OptIn(UnstableApi::class)
private fun AudioCapabilities.supportsPassthrough(mime: String, audioAttributes: AudioAttributes): Boolean {
	val format = Format.Builder()
		.setSampleMimeType(mime)
		.setChannelCount(Integer.bitCount(AudioFormat.CHANNEL_OUT_STEREO))
		.setSampleRate(Format.NO_VALUE)
		.build()
	return isPassthroughPlaybackSupported(format, audioAttributes)
}
