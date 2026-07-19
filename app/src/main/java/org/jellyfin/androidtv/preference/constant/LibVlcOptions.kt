package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.libVlcAudioTimeStretch
import org.jellyfin.androidtv.preference.libVlcDav1dThreadFrames
import org.jellyfin.androidtv.preference.libVlcDeblocking
import org.jellyfin.androidtv.preference.libVlcFrameSkip
import org.jellyfin.androidtv.preference.libVlcReplayGain
import org.jellyfin.androidtv.preference.libVlcReplayGainDefault
import org.jellyfin.androidtv.preference.libVlcReplayGainMode
import org.jellyfin.androidtv.preference.libVlcReplayGainPeakProtection
import org.jellyfin.androidtv.preference.libVlcReplayGainPreamp
import org.jellyfin.androidtv.preference.libVlcVideoOutput
import org.jellyfin.playback.libvlc.LibVlcPlaybackOptions
import org.jellyfin.playback.libvlc.LibVlcVideoDecoder
import org.jellyfin.preference.PreferenceEnum

enum class LibVlcDecoder(
	override val nameRes: Int,
	val descriptionRes: Int,
	val decoder: LibVlcVideoDecoder,
) : PreferenceEnum {
	AUTOMATIC(
		nameRes = R.string.preference_libvlc_decoder_automatic,
		descriptionRes = R.string.preference_libvlc_decoder_automatic_description,
		decoder = LibVlcVideoDecoder.AUTOMATIC,
	),
	DISABLED(
		nameRes = R.string.preference_libvlc_decoder_disabled,
		descriptionRes = R.string.preference_libvlc_decoder_disabled_description,
		decoder = LibVlcVideoDecoder.DISABLED,
	),
	DECODING(
		nameRes = R.string.preference_libvlc_decoder_decoding,
		descriptionRes = R.string.preference_libvlc_decoder_decoding_description,
		decoder = LibVlcVideoDecoder.DECODING,
	),
	FULL(
		nameRes = R.string.preference_libvlc_decoder_full,
		descriptionRes = R.string.preference_libvlc_decoder_full_description,
		decoder = LibVlcVideoDecoder.FULL,
	),
}

enum class LibVlcVideoOutput(
	override val nameRes: Int,
	val descriptionRes: Int,
	val libVlcOption: String?,
) : PreferenceEnum {
	AUTOMATIC(
		nameRes = R.string.preference_libvlc_video_output_automatic,
		descriptionRes = R.string.preference_libvlc_video_output_automatic_description,
		libVlcOption = null,
	),
	OPENGL(
		nameRes = R.string.preference_libvlc_video_output_opengl,
		descriptionRes = R.string.preference_libvlc_video_output_opengl_description,
		libVlcOption = "--vout=gles2,none",
	),
	ANDROID_DISPLAY(
		nameRes = R.string.preference_libvlc_video_output_android_display,
		descriptionRes = R.string.preference_libvlc_video_output_android_display_description,
		libVlcOption = "--vout=android_display,none",
	),
}

enum class LibVlcAudioOutput(
	override val nameRes: Int,
	val descriptionRes: Int,
	val vlcValue: String?,
) : PreferenceEnum {
	AAUDIO(
		nameRes = R.string.preference_libvlc_audio_output_aaudio,
		descriptionRes = R.string.preference_libvlc_audio_output_aaudio_description,
		vlcValue = null,
	),
	AUDIOTRACK(
		nameRes = R.string.preference_libvlc_audio_output_audiotrack,
		descriptionRes = R.string.preference_libvlc_audio_output_audiotrack_description,
		vlcValue = "audiotrack",
	),
	OPENSLES(
		nameRes = R.string.preference_libvlc_audio_output_opensles,
		descriptionRes = R.string.preference_libvlc_audio_output_opensles_description,
		vlcValue = "opensles",
	),
}

enum class LibVlcReplayGainMode(
	override val nameRes: Int,
	val descriptionRes: Int,
	val vlcValue: String,
) : PreferenceEnum {
	TRACK(
		nameRes = R.string.preference_libvlc_replay_gain_mode_track,
		descriptionRes = R.string.preference_libvlc_replay_gain_mode_track_description,
		vlcValue = "track",
	),
	ALBUM(
		nameRes = R.string.preference_libvlc_replay_gain_mode_album,
		descriptionRes = R.string.preference_libvlc_replay_gain_mode_album_description,
		vlcValue = "album",
	),
}

fun UserPreferences.libVlcStartupOptions() = buildList {
	this@libVlcStartupOptions[UserPreferences.libVlcVideoOutput].libVlcOption?.let(::add)
	if (this@libVlcStartupOptions[UserPreferences.libVlcReplayGain]) {
		add("--audio-replay-gain-mode=${this@libVlcStartupOptions[UserPreferences.libVlcReplayGainMode].vlcValue}")
		add("--audio-replay-gain-preamp=${this@libVlcStartupOptions[UserPreferences.libVlcReplayGainPreamp].coerceIn(-20f, 20f)}")
		add("--audio-replay-gain-default=${this@libVlcStartupOptions[UserPreferences.libVlcReplayGainDefault].coerceIn(-20f, 20f)}")
		add(
			if (this@libVlcStartupOptions[UserPreferences.libVlcReplayGainPeakProtection]) {
				"--audio-replay-gain-peak-protection"
			} else {
				"--no-audio-replay-gain-peak-protection"
			},
		)
	}
}

fun UserPreferences.libVlcPlaybackOptions(
	deblocking: LibVlcDeblocking = this[UserPreferences.libVlcDeblocking],
	frameSkip: Boolean = this[UserPreferences.libVlcFrameSkip],
	audioTimeStretch: Boolean = this[UserPreferences.libVlcAudioTimeStretch],
	dav1dThreadFrames: Int = this[UserPreferences.libVlcDav1dThreadFrames],
) = LibVlcPlaybackOptions(
	deblocking = deblocking.vlcValue,
	frameSkip = frameSkip,
	audioTimeStretch = audioTimeStretch,
	dav1dThreadFrames = dav1dThreadFrames.coerceIn(0, 16),
)

enum class LibVlcDeblocking(
	override val nameRes: Int,
	val descriptionRes: Int,
	val vlcValue: Int,
) : PreferenceEnum {
	AUTOMATIC(
		nameRes = R.string.preference_libvlc_deblocking_automatic,
		descriptionRes = R.string.preference_libvlc_deblocking_automatic_description,
		vlcValue = -1,
	),
	FULL(
		nameRes = R.string.preference_libvlc_deblocking_full,
		descriptionRes = R.string.preference_libvlc_deblocking_full_description,
		vlcValue = 0,
	),
	MEDIUM(
		nameRes = R.string.preference_libvlc_deblocking_medium,
		descriptionRes = R.string.preference_libvlc_deblocking_medium_description,
		vlcValue = 1,
	),
	LOW(
		nameRes = R.string.preference_libvlc_deblocking_low,
		descriptionRes = R.string.preference_libvlc_deblocking_low_description,
		vlcValue = 3,
	),
	NONE(
		nameRes = R.string.preference_libvlc_deblocking_none,
		descriptionRes = R.string.preference_libvlc_deblocking_none_description,
		vlcValue = 4,
	),
}
