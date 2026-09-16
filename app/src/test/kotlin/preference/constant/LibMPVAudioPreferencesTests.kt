package org.jellyfin.androidtv.preference.constant

import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.mpvAudioPitchCorrection
import org.jellyfin.androidtv.preference.mpvAudioOutput
import org.jellyfin.androidtv.preference.mpvAudioPreset
import org.jellyfin.androidtv.preference.mpvDeband
import org.jellyfin.androidtv.preference.mpvDecoderThreads
import org.jellyfin.androidtv.preference.mpvDeinterlace
import org.jellyfin.androidtv.preference.mpvFrameDrop
import org.jellyfin.androidtv.preference.mpvGpuApi
import org.jellyfin.androidtv.preference.mpvGpuContext
import org.jellyfin.androidtv.preference.mpvInterpolation
import org.jellyfin.androidtv.preference.mpvLoopFilter
import org.jellyfin.androidtv.preference.mpvNvidiaShieldWorkarounds
import org.jellyfin.androidtv.preference.mpvOptionOverrides
import org.jellyfin.androidtv.preference.mpvReplayGain
import org.jellyfin.androidtv.preference.mpvScaler
import org.jellyfin.androidtv.preference.mpvSoftwareDecodingForLiveTv
import org.jellyfin.androidtv.preference.mpvSubtitleAssOverride
import org.jellyfin.androidtv.preference.mpvSubtitleUseMargins
import org.jellyfin.androidtv.preference.mpvToneMapping
import org.jellyfin.androidtv.preference.mpvVideoOutput
import org.jellyfin.androidtv.preference.mpvVideoPreset
import org.jellyfin.androidtv.preference.mpvVideoSync
import org.jellyfin.playback.mpv.LibMPVAudioPreset

class LibMPVAudioPreferencesTests : FunSpec({
	test("direct audio preserves source channels and enabled detected codecs") {
		val policy = preferences().mpvAudioPolicy(
			setOf(
				MimeTypes.AUDIO_AC3,
				MimeTypes.AUDIO_E_AC3,
				MimeTypes.AUDIO_DTS_HD,
				MimeTypes.AUDIO_TRUEHD,
			)
		)

		policy.audioChannels shouldBe "auto"
		policy.audioSpdif shouldBe "ac3,eac3,dts-hd,truehd"
		policy.audioPreset shouldBe LibMPVAudioPreset.OFF
	}

	test("passthrough includes only codecs supported by the current Android output") {
		preferences().mpvAudioPolicy(
			setOf(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_DTS)
		).audioSpdif shouldBe "eac3,dts"
	}

	test("DTS core and DTS-HD remain independently available") {
		preferences().mpvAudioPolicy(
			setOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD)
		).audioSpdif shouldBe "dts,dts-hd"
	}

	test("passthrough forces AudioTrack when OpenSL ES is selected") {
		preferences(audioOutput = LibMPVAudioOutput.OPENSLES)
			.mpvAudioPolicy(setOf(MimeTypes.AUDIO_AC3))
			.audioOutput shouldBe LibMPVAudioOutput.AUDIOTRACK.mpvValue
	}

	test("passthrough capability does not degrade unrelated decoded PCM") {
		preferences().mpvPlaybackOptions(setOf(MimeTypes.AUDIO_AC3))
			.audioTrackPcmFloat shouldBe true
	}

	test("PCM-only configuration preserves MPV's float output default") {
		preferences().mpvPlaybackOptions(emptySet())
			.audioTrackPcmFloat shouldBe true
	}

	test("PCM playback retains the selected audio output") {
		preferences(audioOutput = LibMPVAudioOutput.OPENSLES)
			.mpvAudioPolicy(emptySet())
			.audioOutput shouldBe LibMPVAudioOutput.OPENSLES.mpvValue
	}

	test("a preset that disables passthrough retains the selected PCM output") {
		val policy = preferences(
			audioOutput = LibMPVAudioOutput.OPENSLES,
			audioPreset = LibMPVAudioPresetOption.CINEMA_SPATIAL,
		).mpvAudioPolicy(setOf(MimeTypes.AUDIO_AC3))

		policy.audioOutput shouldBe LibMPVAudioOutput.OPENSLES.mpvValue
		policy.audioSpdif shouldBe ""
	}

	test("universal codec switches exclude otherwise supported formats") {
		preferences(ac3 = false, dts = false).mpvAudioPolicy(
			setOf(
				MimeTypes.AUDIO_AC3,
				MimeTypes.AUDIO_E_AC3,
				MimeTypes.AUDIO_DTS_HD,
				MimeTypes.AUDIO_TRUEHD,
			)
		).audioSpdif shouldBe "eac3,truehd"
	}

	test("universal stereo downmix overrides passthrough and audio presets") {
		val policy = preferences(
			audioBehavior = AudioBehavior.DOWNMIX_TO_STEREO,
			audioPreset = LibMPVAudioPresetOption.CINEMA_SPATIAL,
		).mpvAudioPolicy(
			setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD)
		)

		policy.audioChannels shouldBe "stereo"
		policy.audioSpdif shouldBe ""
		policy.audioPreset shouldBe LibMPVAudioPreset.OFF
	}

	test("direct audio retains an explicitly selected MPV audio preset") {
		preferences(audioPreset = LibMPVAudioPresetOption.CINEMA_SPATIAL)
			.mpvAudioPolicy(emptySet())
			.audioPreset shouldBe LibMPVAudioPreset.CINEMA_SPATIAL
	}

	test("legacy MPV channel and passthrough routes are unavailable but presets remain") {
		LibMPVChoiceSetting.fromSlug("audio-channels") shouldBe null
		LibMPVChoiceSetting.fromSlug("audio-spdif") shouldBe null
		LibMPVChoiceSetting.fromSlug("audio-preset") shouldBe LibMPVChoiceSetting.AUDIO_PRESET
	}
})

private fun preferences(
	audioBehavior: AudioBehavior = AudioBehavior.DIRECT_STREAM,
	ac3: Boolean = true,
	eac3: Boolean = true,
	dts: Boolean = true,
	truehd: Boolean = true,
	audioOutput: LibMPVAudioOutput = LibMPVAudioOutput.AUTO,
	audioPreset: LibMPVAudioPresetOption = LibMPVAudioPresetOption.OFF,
) = mockk<UserPreferences> {
	every { this@mockk[UserPreferences.audioBehaviour] } returns audioBehavior
	every { this@mockk[UserPreferences.ac3Enabled] } returns ac3
	every { this@mockk[UserPreferences.eac3Enabled] } returns eac3
	every { this@mockk[UserPreferences.dtsEnabled] } returns dts
	every { this@mockk[UserPreferences.truehdEnabled] } returns truehd
	every { this@mockk[UserPreferences.mpvAudioOutput] } returns audioOutput
	every { this@mockk[UserPreferences.mpvAudioPreset] } returns audioPreset
	every { this@mockk[UserPreferences.mpvVideoOutput] } returns LibMPVVideoOutput.GPU_NEXT
	every { this@mockk[UserPreferences.mpvGpuContext] } returns LibMPVGpuContext.ANDROID
	every { this@mockk[UserPreferences.mpvGpuApi] } returns LibMPVGpuApi.AUTO
	every { this@mockk[UserPreferences.mpvVideoSync] } returns LibMPVVideoSync.AUDIO
	every { this@mockk[UserPreferences.mpvFrameDrop] } returns LibMPVFrameDrop.VIDEO_OUTPUT
	every { this@mockk[UserPreferences.mpvDeinterlace] } returns LibMPVDeinterlace.DISABLED
	every { this@mockk[UserPreferences.mpvInterpolation] } returns false
	every { this@mockk[UserPreferences.mpvScaler] } returns LibMPVScaler.BILINEAR
	every { this@mockk[UserPreferences.mpvDeband] } returns false
	every { this@mockk[UserPreferences.mpvToneMapping] } returns LibMPVToneMapping.AUTO
	every { this@mockk[UserPreferences.mpvAudioPitchCorrection] } returns true
	every { this@mockk[UserPreferences.mpvReplayGain] } returns LibMPVReplayGain.DISABLED
	every { this@mockk[UserPreferences.mpvDecoderThreads] } returns 0
	every { this@mockk[UserPreferences.mpvLoopFilter] } returns LibMPVLoopFilter.DEFAULT
	every { this@mockk[UserPreferences.mpvSubtitleAssOverride] } returns LibMPVSubtitleAssOverride.NO
	every { this@mockk[UserPreferences.mpvSubtitleUseMargins] } returns true
	every { this@mockk[UserPreferences.mpvSoftwareDecodingForLiveTv] } returns false
	every { this@mockk[UserPreferences.mpvNvidiaShieldWorkarounds] } returns true
	every { this@mockk[UserPreferences.mpvVideoPreset] } returns LibMPVVideoPresetOption.OFF
	every { this@mockk[UserPreferences.mpvOptionOverrides] } returns ""
}
