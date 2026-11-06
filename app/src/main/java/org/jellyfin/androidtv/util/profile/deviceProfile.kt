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
import androidx.media3.exoplayer.audio.AudioCapabilities
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.AudioBehavior
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.constant.BitstreamAudioMode
import org.jellyfin.androidtv.preference.constant.HdrOverrideMode
import org.jellyfin.androidtv.preference.constant.PlaybackResolution
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.deviceprofile.DeviceProfileBuilder
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile
import org.jellyfin.playback.dovi.DoviRoute
import org.jellyfin.playback.dovi.DoviVideoCodec
import kotlin.math.roundToInt

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

private val passthroughAudioCodecMimes = mapOf(
	MimeTypes.AUDIO_AC3 to setOf(Codec.Audio.AC3),
	MimeTypes.AUDIO_AC4 to setOf(Codec.Audio.AC4),
	MimeTypes.AUDIO_E_AC3 to setOf(Codec.Audio.EAC3),
	MimeTypes.AUDIO_DTS to setOf(Codec.Audio.DCA, Codec.Audio.DTS),
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

private fun UserPreferences.isBitstreamAudioEnabled(context: Context, format: BitstreamAudioFormat): Boolean =
	when (this[format.preference]) {
		BitstreamAudioMode.AUTO -> isPassthroughAudioAvailable(context, format.mimeType)
		BitstreamAudioMode.ENABLE -> true
		BitstreamAudioMode.DISABLE -> false
	}

@JvmOverloads
internal fun createDeviceProfile(
	context: Context,
	userPreferences: UserPreferences,
	serverVersion: ServerVersion,
	doviPlaybackPlan: DoviPlaybackPlan? = null,
	softwareCodecsEnabled: Boolean = userPreferences[UserPreferences.softwareCodecsEnabled],
	mediaTest: MediaCodecCapabilitiesTest = MediaCodecCapabilitiesTest(softwareCodecsEnabled),
): DeviceProfile {
	return createDeviceProfile(
		mediaTest = mediaTest,
		maxBitrate = userPreferences.getMaxBitrate(),
		maxResolution = userPreferences[UserPreferences.maxResolution],
		isAC3PrefEnabled = userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.AC3),
		isEAC3PrefEnabled = userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.EAC3),
		isDTSPrefEnabled = userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.DTS),
		isTrueHDPrefEnabled = userPreferences.isBitstreamAudioEnabled(context, BitstreamAudioFormat.TRUEHD),
		downMixAudio = userPreferences[UserPreferences.audioBehaviour] == AudioBehavior.DOWNMIX_TO_STEREO,
		assDirectPlay = userPreferences[UserPreferences.assDirectPlay],
		pgsDirectPlay = userPreferences[UserPreferences.pgsDirectPlay],
		userAVCLevel = userPreferences[UserPreferences.userAVCLevel].level,
		userHEVCLevel = userPreferences[UserPreferences.userHEVCLevel].level,
		forceEnabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.ENABLE),
		forceDisabledHdr = userPreferences.getHdrRangeTypesFor(HdrOverrideMode.DISABLE),
		doviPlaybackPlan = doviPlaybackPlan,
		passthroughAudioCodecs = getPassthroughAudioCodecs(context),
	)
}

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
) = buildDeviceProfile {
	val supportsOpus = mediaTest.supportsOpus()
	val locallyDecodablePassthroughAudioCodecs = passthroughAudioCodecMimes.entries
		.filter { (mime) -> mediaTest.supportsMimeType(mime) }
		.flatMapTo(mutableSetOf()) { (_, codecs) -> codecs }
	val enabledPassthroughCodecs = enabledPassthroughAudioCodecs(
		isAC3Enabled = isAC3PrefEnabled,
		isEAC3Enabled = isEAC3PrefEnabled,
		isDTSEnabled = isDTSPrefEnabled,
		isTrueHDEnabled = isTrueHDPrefEnabled,
	)
	val allowedAudioCodecs = when {
		downMixAudio -> downmixSupportedAudioCodecs
		else -> supportedAudioCodecs.filterNot { audioCodec ->
			val isPassthroughCodec = audioCodec in allPassthroughAudioCodecs
			val canDecodeLocally = audioCodec in locallyDecodablePassthroughAudioCodecs
			val canPassthrough = audioCodec in passthroughAudioCodecs && audioCodec in enabledPassthroughCodecs
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
	val supportsVP8 = mediaTest.supportsVp8()
	val supportsVP9 = mediaTest.supportsVp9()
	val maxResolutionAVC = mediaTest.getMaxResolution(MimeTypes.VIDEO_H264).capTo(maxResolution)
	val maxResolutionHevc = mediaTest.getMaxResolution(MimeTypes.VIDEO_H265).capTo(maxResolution)
	val maxHevcWidth = if (attemptSelectedHevcSource) maxResolution.maxWidth else maxResolutionHevc.width
	val maxHevcHeight = if (attemptSelectedHevcSource) maxResolution.maxHeight else maxResolutionHevc.height
	val maxResolutionAV1 = mediaTest.getMaxResolution(MimeTypes.VIDEO_AV1).capTo(maxResolution)
	val maxResolutionVC1 = mediaTest.getMaxResolution(MimeTypes.VIDEO_VC1).capTo(maxResolution)

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
	val hlsVideoCodecs = listOfNotNull(
		if (supportsHevc) Codec.Video.HEVC else null,
		Codec.Video.H264
	).toTypedArray()

	transcodingProfile {
		type = DlnaProfileType.VIDEO
		context = EncodingContext.STREAMING

		container = Codec.Container.TS
		protocol = MediaStreamProtocol.HLS

		videoCodec(*hlsVideoCodecs)
		audioCodec(*hlsMpegTsAudioCodecs.filter(allowedAudioCodecs::contains).toTypedArray())

		copyTimestamps = false
		enableSubtitlesInManifest = true
	}

	transcodingProfile {
		type = DlnaProfileType.VIDEO
		context = EncodingContext.STREAMING

		container = Codec.Container.MP4
		protocol = MediaStreamProtocol.HLS

		videoCodec(*hlsVideoCodecs)
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
				!supportsAV1Main10 -> ProfileConditionValue.VIDEO_PROFILE notEquals "main 10"
				else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
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

	for ((videoCodec, supported) in arrayOf(
		Codec.Video.MPEG4 to supportsMpeg4Asp,
		Codec.Video.VP8 to supportsVP8,
		Codec.Video.VP9 to supportsVP9,
	)) {
		codecProfile {
			type = CodecType.VIDEO
			codec = videoCodec

			conditions {
				when {
					!supported -> ProfileConditionValue.VIDEO_PROFILE equals "none"
					else -> ProfileConditionValue.VIDEO_PROFILE notEquals "none"
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

	/// HDR exclude list

	val unsupportedRangeTypesAv1 = buildSet {
		add(VideoRangeType.DOVI_INVALID)

		if (!supportsAV1DolbyVision) {
			add(VideoRangeType.DOVI)
			if (!supportsAV1HDR10) add(VideoRangeType.DOVI_WITH_HDR10)
			if (!supportsAV1HDR10Plus) add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
		}

		if (!supportsAV1HDR10Plus) {
			add(VideoRangeType.HDR10_PLUS)

			if (!supportsAV1HDR10) add(VideoRangeType.HDR10)
		}
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

	// Note: The codec profiles use a workaround to create correct behavior
	// The notEquals condition will always fail the ConditionProcessor test in the server so we use applyConditions to only have the codec
	// profile be active when the media in question uses one of the unsupported range types. The server will then use the value of the
	// notEquals in the StreamBuilder to create a correct transcode pipeline

	// Codecs
	// AV1
	if (unsupportedRangeTypesAv1.isNotEmpty()) codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.AV1

		conditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE notEquals unsupportedRangeTypesAv1.joinToString("|") { it.serialName }
		}

		applyConditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE inCollection unsupportedRangeTypesAv1.map { it.serialName }
		}
	}

	// HEVC
	if (unsupportedRangeTypesHevc.isNotEmpty()) codecProfile {
		type = CodecType.VIDEO
		codec = Codec.Video.HEVC

		conditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE notEquals unsupportedRangeTypesHevc.joinToString("|") { it.serialName }
		}

		applyConditions {
			ProfileConditionValue.VIDEO_RANGE_TYPE inCollection unsupportedRangeTypesHevc.map { it.serialName }
		}
	}

	// Audio channel profile
	codecProfile {
		type = CodecType.VIDEO_AUDIO

		conditions {
			ProfileConditionValue.AUDIO_CHANNELS lowerThanOrEquals if (downMixAudio) 2 else 8
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
fun isPassthroughAudioAvailable(context: Context, mimetype: String): Boolean {
	val audioAttributes = passthroughAudioAttributes()
	return getAudioCapabilities(context, audioAttributes).supportsPassthrough(mimetype, audioAttributes)
}

@OptIn(UnstableApi::class)
private fun getPassthroughAudioCodecs(context: Context): Set<String> {
	val audioAttributes = passthroughAudioAttributes()
	val audioCapabilities = getAudioCapabilities(context, audioAttributes)
	return passthroughAudioCodecMimes.entries.flatMapTo(mutableSetOf()) { (mime, codecs) ->
		if (audioCapabilities.supportsPassthrough(mime, audioAttributes)) codecs else emptySet()
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
