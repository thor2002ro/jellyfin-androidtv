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
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BitstreamAudioFormat
import org.jellyfin.androidtv.preference.constant.BitstreamAudioMode
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue

class DeviceProfileCompatibilityTests : FunSpec({
	afterTest { unmockkConstructor(Size::class) }

	test("server profile honors forced codec families without detected support or local decoders") {
		val codecsByFormat = mapOf(
			BitstreamAudioFormat.AC3 to setOf(Codec.Audio.AC3),
			BitstreamAudioFormat.EAC3 to setOf(Codec.Audio.EAC3),
			BitstreamAudioFormat.DTS to setOf(Codec.Audio.DCA, Codec.Audio.DTS),
			BitstreamAudioFormat.TRUEHD to setOf(Codec.Audio.MLP, Codec.Audio.TRUEHD),
		)
		for ((format, codecs) in codecsByFormat) for (mode in BitstreamAudioMode.entries) {
			val preferences = mockk<UserPreferences>()
			for (entry in BitstreamAudioFormat.entries) {
				every { preferences[entry.preference] } returns if (entry == format) mode else BitstreamAudioMode.AUTO
			}
			val resolved = preferences.profilePassthroughAudioCodecs(emptySet())
			resolved shouldBe if (mode == BitstreamAudioMode.ENABLE) codecs else emptySet()
			val announced = deviceProfile(passthroughAudioCodecs = resolved).announcedAudioCodecs()
			for (codec in codecs) (codec in announced) shouldBe (mode == BitstreamAudioMode.ENABLE)
			val downmixed = deviceProfile(downMixAudio = true, passthroughAudioCodecs = resolved).announcedAudioCodecs()
			for (codec in codecs) (codec in downmixed) shouldBe false
		}
	}

	test("forced EAC3 respects disabled AC3 and detected codec aliases survive auto mode") {
		val preferences = mockk<UserPreferences>()
		for (entry in BitstreamAudioFormat.entries) {
			every { preferences[entry.preference] } returns BitstreamAudioMode.AUTO
		}
		preferences.profilePassthroughAudioCodecs(setOf(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS_HD)) shouldBe
			setOf(Codec.Audio.EAC3, Codec.Audio.DCA, Codec.Audio.DTS)
		every { preferences[UserPreferences.bitstreamAc3] } returns BitstreamAudioMode.DISABLE
		every { preferences[UserPreferences.bitstreamEac3] } returns BitstreamAudioMode.ENABLE
		preferences.profilePassthroughAudioCodecs(emptySet()) shouldBe emptySet()
	}

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
			)
	}

	test("audio transcoding uses fMP4 HLS") {
		val audioTranscode = deviceProfile().transcodingProfiles.single { it.type == DlnaProfileType.AUDIO }

		audioTranscode.container shouldBe Codec.Container.MP4
		audioTranscode.protocol shouldBe MediaStreamProtocol.HLS
		audioTranscode.audioCodec shouldBe Codec.Audio.AAC
	}

	test("video transcoding profiles only advertise practical encoder codecs") {
		val supportedProfile = deviceProfile(
			supportsAv1 = true,
			supportedVideoMimes = setOf(MimeTypes.VIDEO_VP9),
		)

		supportedProfile.videoTranscodeProfile(Codec.Container.TS).videoCodec.declarations() shouldBe listOf("h264")
		supportedProfile.videoTranscodeProfile(Codec.Container.MP4).videoCodec.declarations() shouldBe listOf("h264")
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
	mpeg4AspSupported: Boolean = false,
	supportedAudioMimes: Set<String> = emptySet(),
	supportedVideoMimes: Set<String> = emptySet(),
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
	every { anyConstructed<Size>().width } returns 3840
	every { anyConstructed<Size>().height } returns 2160
	val size = mockk<Size> {
		every { width } returns 3840
		every { height } returns 2160
	}
	val mediaTest = mockk<MediaCodecCapabilitiesTest>(relaxed = true) {
		every { getMaxResolution(any()) } returns size
		every { supportsAV1() } returns supportsAv1
		every { supportsMpeg2() } returns (MimeTypes.VIDEO_MPEG2 in supportedVideoMimes)
		every { supportsMpeg4Asp() } returns mpeg4AspSupported
		every { supportsOpus() } returns true
		every { supportsVp8() } returns (MimeTypes.VIDEO_VP8 in supportedVideoMimes)
		every { supportsVp9() } returns (MimeTypes.VIDEO_VP9 in supportedVideoMimes)
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
		forceEnabledHdr = emptySet(),
		forceDisabledHdr = emptySet(),
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

private fun String?.declarations() = orEmpty().split(',').filter(String::isNotBlank)
