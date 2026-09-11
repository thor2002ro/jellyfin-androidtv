package org.jellyfin.androidtv.util.profile

import android.util.Size
import androidx.media3.common.MimeTypes
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.jellyfin.androidtv.constant.Codec
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.VideoRangeType

class DeviceProfileCompatibilityTests : FunSpec({
	afterTest { unmockkConstructor(Size::class) }

	test("audio direct play is limited to containers Media3 can demux") {
		val profile = deviceProfile()

		profile.generalAudioProfile().container.declarations() shouldContainExactlyInAnyOrder listOf(
			"aac",
			"ac3",
			"ac4",
			"eac3",
			"flac",
			"flv",
			"hls",
			"m4a",
			"mkv",
			"mov",
			"mp3",
			"mp4",
			"ogg",
			"ts",
			"wav",
			"webm",
		)
	}

	test("fMP4 audio codecs have individual remux profiles") {
		val profile = deviceProfile()

		profile.directPlayProfiles
			.filter { it.type == DlnaProfileType.AUDIO && it.container == Codec.Container.MP4 }
			.mapNotNull { it.audioCodec }
			.filterNot { ',' in it }
			.shouldContainExactlyInAnyOrder(
				Codec.Audio.AAC,
				Codec.Audio.AC3,
				Codec.Audio.EAC3,
				Codec.Audio.MP3,
				Codec.Audio.ALAC,
				Codec.Audio.FLAC,
				Codec.Audio.OPUS,
				Codec.Audio.DTS,
				Codec.Audio.TRUEHD,
			)
	}

	test("audio transcoding uses fMP4 HLS") {
		val audioTranscode = deviceProfile().transcodingProfiles.single { it.type == DlnaProfileType.AUDIO }

		audioTranscode.container shouldBe Codec.Container.MP4
		audioTranscode.protocol shouldBe MediaStreamProtocol.HLS
		audioTranscode.audioCodec shouldBe Codec.Audio.AAC
	}

	test("MPEG-TS video profile only advertises practical encoder codecs") {
		val supportedProfile = deviceProfile(
			supportsAv1 = true,
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
		)

		supportedProfile.videoTranscodeProfile(Codec.Container.TS).videoCodec.declarations() shouldBe listOf("h264")
	}

	test("fMP4 video profile advertises decoder-supported AV1 and VP9 for stream copy") {
		val supportedProfile = deviceProfile(
			supportsAv1 = true,
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
		)

		supportedProfile.videoTranscodeProfile(Codec.Container.MP4).videoCodec.declarations() shouldBe listOf(
			"h264",
			"av1",
			"vp9",
		)
	}

	test("fMP4 video profile omits AV1 and VP9 without device decoders") {
		deviceProfile().videoTranscodeProfile(Codec.Container.MP4).videoCodec.declarations() shouldBe listOf("h264")
	}

	test("AV1 uses the Server 12 Main profile name and decoder bit depth") {
		val main8Conditions = deviceProfile(supportsAv1 = true).videoCodecConditions(Codec.Video.AV1)
		val main10Conditions = deviceProfile(
			supportsAv1 = true,
			supportsAv1Main10 = true,
		).videoCodecConditions(Codec.Video.AV1)

		main8Conditions.single { it.property == ProfileConditionValue.VIDEO_PROFILE }.apply {
			condition shouldBe ProfileConditionType.EQUALS
			value shouldBe "main"
		}
		main8Conditions.single { it.property == ProfileConditionValue.VIDEO_BIT_DEPTH }.apply {
			condition shouldBe ProfileConditionType.LESS_THAN_EQUAL
			value shouldBe "8"
		}
		main10Conditions.single { it.property == ProfileConditionValue.VIDEO_BIT_DEPTH }.value shouldBe "10"
	}

	test("AV1 HDR10 and HDR10 Plus range claims are independent") {
		val hdr10PlusOnly = deviceProfile(
			supportsAv1 = true,
			supportsAv1Main10 = true,
			supportsAv1Hdr10Plus = true,
		)
		val unsupportedRanges = hdr10PlusOnly.unsupportedRanges(Codec.Video.AV1)

		unsupportedRanges.contains(VideoRangeType.HDR10.serialName) shouldBe true
		unsupportedRanges.contains(VideoRangeType.HDR10_PLUS.serialName) shouldBe false
		hdr10PlusOnly.acceptsRange(Codec.Video.AV1, VideoRangeType.HDR10_PLUS) shouldBe true
		hdr10PlusOnly.acceptsRange(Codec.Video.AV1, VideoRangeType.HDR10) shouldBe false
	}

	test("Server 12 rejects a singleton unsupported HDR10 Plus range") {
		val profile = deviceProfile(
			supportsVp9Main10 = true,
			supportsVp9Hdr = true,
			forceDisabledHdr = setOf(VideoRangeType.HDR10_PLUS),
		)
		profile.acceptsRange(Codec.Video.VP9, VideoRangeType.HDR10_PLUS) shouldBe false
		profile.acceptsRange(Codec.Video.VP9, VideoRangeType.HDR10) shouldBe true
		profile.acceptsRange(Codec.Video.VP9, VideoRangeType.SDR) shouldBe true
	}

	test("HDR10 decoders keep HDR10 Plus base-layer playback without native dynamic metadata") {
		val profile = deviceProfile(supportsAv1Hdr10 = true, supportsVp9Hdr = true, supportsHevcHdr10 = true)
		for (codec in listOf(Codec.Video.AV1, Codec.Video.VP9, Codec.Video.HEVC)) withClue(codec) {
			profile.acceptsRange(codec, VideoRangeType.HDR10_PLUS) shouldBe true
		}
	}

	test("Dolby Vision fallback does not offer an unsupported HDR10 transcode output") {
		val profile = deviceProfile(supportsAv1 = true, supportsAv1Main10 = true, supportsAv1Hdr10Plus = true)
		val failedConditions = profile.codecProfiles
			.filter { it.codec == Codec.Video.AV1 && it.applyConditions.isNotEmpty() }
			.filter { it.applyConditions.all { condition -> condition.matchesServer12Range(VideoRangeType.DOVI_WITH_HDR10) } }
			.flatMap { it.conditions }
			.filter { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }
			.filterNot { it.matchesServer12Range(VideoRangeType.DOVI_WITH_HDR10) }
		// StreamBuilder uses NotEquals values as the exclusions for the encoder's rangetype option.
		val excludedOutputs = failedConditions.last().value.orEmpty().split('|').filter(String::isNotEmpty)
		(VideoRangeType.HDR10.serialName in excludedOutputs) shouldBe true
		(VideoRangeType.HDR10_PLUS.serialName in excludedOutputs) shouldBe false
	}

	test("Server 12 HDR exclusions honor both overrides independently for every video codec") {
		val hdrRanges = setOf(VideoRangeType.HDR10, VideoRangeType.HDR10_PLUS)
		val cases = listOf(
			Triple(emptySet(), true, true),
			Triple(setOf(VideoRangeType.HDR10), false, true),
			Triple(setOf(VideoRangeType.HDR10_PLUS), true, false),
			Triple(hdrRanges, false, false),
		)
		for ((disabled, acceptsHdr10, acceptsHdr10Plus) in cases) {
			val profile = deviceProfile(
				forceEnabledHdr = hdrRanges - disabled,
				forceDisabledHdr = disabled + VideoRangeType.DOVI_INVALID,
			)
			for (codec in listOf(Codec.Video.AV1, Codec.Video.VP9, Codec.Video.HEVC)) withClue("$codec disabled=$disabled") {
				profile.acceptsRange(codec, VideoRangeType.HDR10) shouldBe acceptsHdr10
				profile.acceptsRange(codec, VideoRangeType.HDR10_PLUS) shouldBe acceptsHdr10Plus
				profile.acceptsRange(codec, VideoRangeType.SDR) shouldBe true
				profile.acceptsRange(codec, VideoRangeType.DOVI_INVALID) shouldBe false
			}
		}
	}

	test("VP9 advertises only decoder-supported profiles and limits") {
		val main8Conditions = deviceProfile(
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
			maxVideoWidth = 1920,
			maxVideoHeight = 1080,
		).videoCodecConditions(Codec.Video.VP9)
		val main10Conditions = deviceProfile(
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
			supportsVp9Main10 = true,
		).videoCodecConditions(Codec.Video.VP9)

		main8Conditions.single { it.property == ProfileConditionValue.VIDEO_PROFILE }.apply {
			condition shouldBe ProfileConditionType.EQUALS
			value shouldBe "profile 0"
		}
		main8Conditions.single { it.property == ProfileConditionValue.VIDEO_BIT_DEPTH }.value shouldBe "8"
		main8Conditions.single { it.property == ProfileConditionValue.WIDTH }.value shouldBe "1920"
		main8Conditions.single { it.property == ProfileConditionValue.HEIGHT }.value shouldBe "1080"
		main10Conditions.single { it.property == ProfileConditionValue.VIDEO_PROFILE }
			.value.orEmpty().split('|') shouldBe listOf("profile 0", "profile 2")
		main10Conditions.single { it.property == ProfileConditionValue.VIDEO_BIT_DEPTH }.value shouldBe "10"
	}

	test("VP9 Profile 2 alone does not advertise Profile 0") {
		val conditions = deviceProfile(supportsVp9Main10 = true).videoCodecConditions(Codec.Video.VP9)
		conditions.single { it.property == ProfileConditionValue.VIDEO_PROFILE }.value shouldBe "profile 2"
	}

	test("MPEG4 Simple decoder permits Simple but not Advanced Simple content") {
		val conditions = deviceProfile(mpeg4SimpleSupported = true).videoCodecConditions(Codec.Video.MPEG4)
		conditions.single { it.property == ProfileConditionValue.VIDEO_PROFILE }.value shouldBe "Simple Profile"
	}

	test("MPEG4 Advanced Simple decoder does not advertise unrelated profiles") {
		val conditions = deviceProfile(mpeg4AspSupported = true).videoCodecConditions(Codec.Video.MPEG4)
		conditions.single { it.property == ProfileConditionValue.VIDEO_PROFILE }.value shouldBe "Advanced Simple Profile"
	}

	test("VP9 rejects HDR ranges not exposed by the decoder") {
		val plainMain10 = deviceProfile(
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
			supportsVp9Main10 = true,
		)
		val hdr = deviceProfile(
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
			supportsVp9Main10 = true,
			supportsVp9Hdr = true,
		)
		val hdr10Plus = deviceProfile(
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
			supportsVp9Main10 = true,
			supportsVp9Hdr = true,
			supportsVp9Hdr10Plus = true,
		)
		val hdr10PlusOnly = deviceProfile(
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
			supportsVp9Main10 = true,
			supportsVp9Hdr10Plus = true,
		)

		plainMain10.unsupportedRanges(Codec.Video.VP9) shouldBe setOf(
			VideoRangeType.HDR10.serialName,
			VideoRangeType.HDR10_PLUS.serialName,
		)
		hdr.unsupportedRanges(Codec.Video.VP9) shouldBe emptySet()
		hdr10Plus.unsupportedRanges(Codec.Video.VP9) shouldBe emptySet()
		hdr10PlusOnly.unsupportedRanges(Codec.Video.VP9) shouldBe setOf(VideoRangeType.HDR10.serialName)
	}

	test("disabled passthrough codecs remain available when locally decodable") {
		val profile = deviceProfile(
			ac3 = false,
			eac3 = false,
			dts = false,
			truehd = false,
			supportedAudioMimes = setOf(
				MimeTypes.AUDIO_AC3,
				MimeTypes.AUDIO_E_AC3,
				MimeTypes.AUDIO_DTS,
				MimeTypes.AUDIO_TRUEHD,
			),
		)
		val announcedAudioCodecs = profile.announcedAudioCodecs()

		announcedAudioCodecs.filter { it in setOf(
			Codec.Audio.AC3,
			Codec.Audio.EAC3,
			Codec.Audio.DCA,
			Codec.Audio.DTS,
			Codec.Audio.MLP,
			Codec.Audio.TRUEHD,
		) }.toSet() shouldBe setOf(
			Codec.Audio.AC3,
			Codec.Audio.EAC3,
			Codec.Audio.DCA,
			Codec.Audio.DTS,
			Codec.Audio.MLP,
			Codec.Audio.TRUEHD,
		)
	}

	test("unavailable passthrough codec remains available when locally decodable") {
		val profile = deviceProfile(
			supportedAudioMimes = setOf(MimeTypes.AUDIO_AC3),
			passthroughAudioCodecs = emptySet(),
		)

		(Codec.Audio.AC3 in profile.announcedAudioCodecs()) shouldBe true
	}

	test("EAC3 JOC decoder keeps the Server 12 EAC3 family available") {
		val profile = deviceProfile(
			eac3 = false,
			supportedAudioMimes = setOf(MimeTypes.AUDIO_E_AC3_JOC),
			passthroughAudioCodecs = emptySet(),
		)

		(Codec.Audio.EAC3 in profile.announcedAudioCodecs()) shouldBe true
	}

	test("DTS Express decoder keeps the Server 12 DTS family available") {
		val profile = deviceProfile(
			dts = false,
			supportedAudioMimes = setOf(MimeTypes.AUDIO_DTS_EXPRESS),
			passthroughAudioCodecs = emptySet(),
		)
		val announcedAudioCodecs = profile.announcedAudioCodecs()

		(Codec.Audio.DCA in announcedAudioCodecs) shouldBe true
		(Codec.Audio.DTS in announcedAudioCodecs) shouldBe true
	}

	test("DTS HD decoder keeps the Server 12 DTS family available") {
		val profile = deviceProfile(
			dts = false,
			supportedAudioMimes = setOf(MimeTypes.AUDIO_DTS_HD),
			passthroughAudioCodecs = emptySet(),
		)
		val announcedAudioCodecs = profile.announcedAudioCodecs()

		(Codec.Audio.DCA in announcedAudioCodecs) shouldBe true
		(Codec.Audio.DTS in announcedAudioCodecs) shouldBe true
	}

	test("DTS UHD decoder alone does not advertise the broader Server 12 DTS family") {
		val profile = deviceProfile(
			dts = false,
			supportedAudioMimes = setOf(MimeTypes.AUDIO_DTS_UHD_P2),
			passthroughAudioCodecs = emptySet(),
		)
		val announcedAudioCodecs = profile.announcedAudioCodecs()

		(Codec.Audio.DCA in announcedAudioCodecs) shouldBe false
		(Codec.Audio.DTS in announcedAudioCodecs) shouldBe false
	}

	test("unavailable and undecodable passthrough codecs are omitted") {
		val profile = deviceProfile(passthroughAudioCodecs = setOf(Codec.Audio.AC3))
		val announcedAudioCodecs = profile.announcedAudioCodecs()

		(Codec.Audio.AAC in announcedAudioCodecs) shouldBe true
		(Codec.Audio.AC3 in announcedAudioCodecs) shouldBe true
		announcedAudioCodecs.any { it in setOf(
			Codec.Audio.AC4,
			Codec.Audio.EAC3,
			Codec.Audio.DCA,
			Codec.Audio.DTS,
			Codec.Audio.MLP,
			Codec.Audio.TRUEHD,
		) } shouldBe false
	}

	test("video direct play uses demuxable containers and server codec names") {
		val profile = deviceProfile()
		val video = profile.directPlayProfiles.single { it.type == DlnaProfileType.VIDEO }

		video.container.declarations() shouldContainExactlyInAnyOrder listOf(
			"avi",
			"flv",
			"hls",
			"m4v",
			"mkv",
			"mov",
			"mp4",
			"mpeg",
			"ts",
			"webm",
		)
		video.videoCodec.declarations() shouldContainExactlyInAnyOrder listOf(
			"av1",
			"h264",
			"hevc",
			"mpeg1video",
			"mpeg2video",
			"mpeg4",
			"vc1",
			"vp8",
			"vp9",
		)
	}

	test("optional video codecs are rejected when the device has no decoder") {
		val profile = deviceProfile()

		listOf("mpeg1video", "mpeg2video", "mpeg4", "vp8", "vp9").forEach { codec ->
			profile.codecProfiles
				.filter { it.codec == codec }
				.flatMap { it.conditions }
				.any {
					it.property == ProfileConditionValue.VIDEO_PROFILE &&
						it.condition == ProfileConditionType.EQUALS &&
						it.value == "none"
				} shouldBe true
		}
	}

	test("optional video codecs remain available when the device has a decoder") {
		val profile = deviceProfile(
			mpeg4AspSupported = true,
			supportedVideoMimes = setOf(
				MimeTypes.VIDEO_MPEG2,
				MimeTypes.VIDEO_VP8,
				MimeTypes.VIDEO_VP9,
			),
		)

		listOf("mpeg1video", "mpeg2video", "mpeg4", "vp8", "vp9").forEach { codec ->
			withClue(codec) {
				profile.codecProfiles
					.filter { it.codec == codec }
					.flatMap { it.conditions }
					.none {
						it.property == ProfileConditionValue.VIDEO_PROFILE &&
							it.condition == ProfileConditionType.EQUALS &&
							it.value == "none"
					} shouldBe true
			}
		}
	}
})

private fun deviceProfile(
	ac3: Boolean = true,
	eac3: Boolean = true,
	dts: Boolean = true,
	truehd: Boolean = true,
	supportsAv1: Boolean = false,
	supportsAv1Main10: Boolean = false,
	supportsAv1Hdr10: Boolean = false,
	supportsAv1Hdr10Plus: Boolean = false,
	supportsVp9Main10: Boolean = false,
	supportsVp9Hdr: Boolean = false,
	supportsVp9Hdr10Plus: Boolean = false,
	supportsHevcHdr10: Boolean = false,
	mpeg4AspSupported: Boolean = false,
	mpeg4SimpleSupported: Boolean = false,
	maxVideoWidth: Int = 3840,
	maxVideoHeight: Int = 2160,
	supportedAudioMimes: Set<String> = emptySet(),
	supportedVideoMimes: Set<String> = emptySet(),
	forceEnabledHdr: Set<VideoRangeType> = emptySet(),
	forceDisabledHdr: Set<VideoRangeType> = emptySet(),
	passthroughAudioCodecs: Set<String> = setOf(
		Codec.Audio.AC3,
		Codec.Audio.AC4,
		Codec.Audio.DCA,
		Codec.Audio.DTS,
		Codec.Audio.EAC3,
		Codec.Audio.MLP,
		Codec.Audio.TRUEHD,
	),
): DeviceProfile {
	mockkConstructor(Size::class)
	every { anyConstructed<Size>().width } returns maxVideoWidth
	every { anyConstructed<Size>().height } returns maxVideoHeight
	val size = mockk<Size> {
		every { width } returns maxVideoWidth
		every { height } returns maxVideoHeight
	}
	val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
		every { getMaxResolution(any()) } returns size
		every { supportsAV1() } returns supportsAv1
		every { supportsAV1Main10() } returns supportsAv1Main10
		every { supportsAV1HDR10() } returns supportsAv1Hdr10
		every { supportsAV1HDR10Plus() } returns supportsAv1Hdr10Plus
		every { supportsMpeg2() } returns (MimeTypes.VIDEO_MPEG2 in supportedVideoMimes)
		every { supportsMpeg4Asp() } returns mpeg4AspSupported
		every { supportsMpeg4Simple() } returns mpeg4SimpleSupported
		every { supportsOpus() } returns true
		every { supportsVp8() } returns (MimeTypes.VIDEO_VP8 in supportedVideoMimes)
		every { supportsVp9() } returns (MimeTypes.VIDEO_VP9 in supportedVideoMimes || supportsVp9Main10)
		every { supportsVp9Main8() } returns (MimeTypes.VIDEO_VP9 in supportedVideoMimes)
		every { supportsVp9Main10() } returns supportsVp9Main10
		every { supportsVp9HDR() } returns supportsVp9Hdr
		every { supportsVp9HDR10Plus() } returns supportsVp9Hdr10Plus
		every { supportsHevcHDR10() } returns supportsHevcHdr10
		every { supportsMimeType(any()) } answers {
			val mime = firstArg<String>()
			mime in supportedAudioMimes || mime in supportedVideoMimes
		}
	}
	return createDeviceProfile(
		mediaTest = mediaTest,
		maxBitrate = 100_000_000,
		isAC3PrefEnabled = ac3,
		isEAC3PrefEnabled = eac3,
		isDTSPrefEnabled = dts,
		isTrueHDPrefEnabled = truehd,
		downMixAudio = false,
		assDirectPlay = true,
		pgsDirectPlay = true,
		userAVCLevel = null,
		userHEVCLevel = null,
		forceEnabledHdr = forceEnabledHdr,
		forceDisabledHdr = forceDisabledHdr,
		passthroughAudioCodecs = passthroughAudioCodecs,
	)
}

private fun DeviceProfile.generalAudioProfile() = directPlayProfiles.single { profile ->
	profile.type == DlnaProfileType.AUDIO && ',' in profile.audioCodec.orEmpty()
}

private fun DeviceProfile.videoTranscodeProfile(container: String) = transcodingProfiles.single { profile ->
	profile.type == DlnaProfileType.VIDEO && profile.container == container
}

private fun DeviceProfile.announcedAudioCodecs() = directPlayProfiles.flatMap { profile ->
	profile.audioCodec.declarations()
}

private fun DeviceProfile.videoCodecConditions(codec: String) = codecProfiles
	.filter { it.codec == codec }
	.flatMap { it.conditions }

private fun DeviceProfile.unsupportedRanges(codec: String) = codecProfiles
	.asSequence()
	.filter { it.codec == codec }
	.flatMap { it.applyConditions.asSequence() }
	.filter { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }
	.flatMap { it.value.orEmpty().split('|').asSequence() }
	.filter(String::isNotEmpty)
	.toSet()

private fun String?.declarations() = orEmpty().split(',').filter(String::isNotBlank)

private fun DeviceProfile.acceptsRange(codec: String, range: VideoRangeType) = codecProfiles
	.filter { it.codec == codec && it.conditions.any { condition -> condition.property == ProfileConditionValue.VIDEO_RANGE_TYPE } }
	.filter { it.applyConditions.all { condition -> condition.matchesServer12Range(range) } }
	.all { it.conditions.all { condition -> condition.matchesServer12Range(range) } }

// Characterizes Server 12 ConditionProcessor's HDR10Plus fallback, including NotEquals.
// https://github.com/jellyfin/jellyfin/blob/v12.0/MediaBrowser.Model/Dlna/ConditionProcessor.cs
private fun ProfileCondition.matchesServer12Range(range: VideoRangeType): Boolean {
	if (range == VideoRangeType.HDR10_PLUS && matchesServer12Range(VideoRangeType.HDR10)) return true
	if (condition == ProfileConditionType.EQUALS_ANY) return range.serialName in value.orEmpty().split('|')
	val expected = VideoRangeType.entries.find { it.serialName == value } ?: return false
	return when (condition) {
		ProfileConditionType.EQUALS -> range == expected
		ProfileConditionType.NOT_EQUALS -> range != expected
		else -> error("Unexpected range condition: $condition")
	}
}
