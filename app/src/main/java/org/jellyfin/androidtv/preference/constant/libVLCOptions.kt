package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.libVLCAudioTimeStretch
import org.jellyfin.androidtv.preference.libVLCDav1dThreadFrames
import org.jellyfin.androidtv.preference.libVLCDeblocking
import org.jellyfin.androidtv.preference.libVLCFrameSkip
import org.jellyfin.androidtv.preference.libVLCReplayGain
import org.jellyfin.androidtv.preference.libVLCReplayGainDefault
import org.jellyfin.androidtv.preference.libVLCReplayGainMode
import org.jellyfin.androidtv.preference.libVLCReplayGainPeakProtection
import org.jellyfin.androidtv.preference.libVLCReplayGainPreamp
import org.jellyfin.androidtv.preference.libVLCVideoOutput
import org.jellyfin.playback.libvlc.LibVLCPlaybackOptions
import org.jellyfin.playback.libvlc.LibVLCVideoDecoder
import org.jellyfin.preference.PreferenceEnum

enum class LibVLCDecoder(
	override val nameRes: Int,
	val descriptionRes: Int,
	val decoder: LibVLCVideoDecoder,
) : PreferenceEnum {
	AUTOMATIC(
		nameRes = R.string.preference_libvlc_decoder_automatic,
		descriptionRes = R.string.preference_libvlc_decoder_automatic_description,
		decoder = LibVLCVideoDecoder.AUTOMATIC,
	),
	DISABLED(
		nameRes = R.string.preference_libvlc_decoder_disabled,
		descriptionRes = R.string.preference_libvlc_decoder_disabled_description,
		decoder = LibVLCVideoDecoder.DISABLED,
	),
	DECODING(
		nameRes = R.string.preference_libvlc_decoder_decoding,
		descriptionRes = R.string.preference_libvlc_decoder_decoding_description,
		decoder = LibVLCVideoDecoder.DECODING,
	),
	FULL(
		nameRes = R.string.preference_libvlc_decoder_full,
		descriptionRes = R.string.preference_libvlc_decoder_full_description,
		decoder = LibVLCVideoDecoder.FULL,
	),
}

enum class LibVLCVideoOutput(
	override val nameRes: Int,
	val descriptionRes: Int,
	val libVLCOption: String?,
) : PreferenceEnum {
	AUTOMATIC(
		nameRes = R.string.preference_libvlc_video_output_automatic,
		descriptionRes = R.string.preference_libvlc_video_output_automatic_description,
		libVLCOption = null,
	),
	OPENGL(
		nameRes = R.string.preference_libvlc_video_output_opengl,
		descriptionRes = R.string.preference_libvlc_video_output_opengl_description,
		libVLCOption = "--vout=gles2,none",
	),
	ANDROID_DISPLAY(
		nameRes = R.string.preference_libvlc_video_output_android_display,
		descriptionRes = R.string.preference_libvlc_video_output_android_display_description,
		libVLCOption = "--vout=android_display,none",
	),
}

enum class LibVLCAudioOutput(
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

enum class LibVLCReplayGainMode(
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

fun UserPreferences.libVLCStartupOptions() = buildList {
	this@libVLCStartupOptions[UserPreferences.libVLCVideoOutput].libVLCOption?.let(::add)
	if (this@libVLCStartupOptions[UserPreferences.libVLCReplayGain]) {
		add("--audio-replay-gain-mode=${this@libVLCStartupOptions[UserPreferences.libVLCReplayGainMode].vlcValue}")
		add("--audio-replay-gain-preamp=${this@libVLCStartupOptions[UserPreferences.libVLCReplayGainPreamp].coerceIn(-20f, 20f)}")
		add("--audio-replay-gain-default=${this@libVLCStartupOptions[UserPreferences.libVLCReplayGainDefault].coerceIn(-20f, 20f)}")
		add(
			if (this@libVLCStartupOptions[UserPreferences.libVLCReplayGainPeakProtection]) {
				"--audio-replay-gain-peak-protection"
			} else {
				"--no-audio-replay-gain-peak-protection"
			},
		)
	}
}

fun UserPreferences.libVLCPlaybackOptions(
	deblocking: LibVLCDeblocking = this[UserPreferences.libVLCDeblocking],
	frameSkip: Boolean = this[UserPreferences.libVLCFrameSkip],
	audioTimeStretch: Boolean = this[UserPreferences.libVLCAudioTimeStretch],
	dav1dThreadFrames: Int = this[UserPreferences.libVLCDav1dThreadFrames],
) = LibVLCPlaybackOptions(
	deblocking = deblocking.vlcValue,
	frameSkip = frameSkip,
	audioTimeStretch = audioTimeStretch,
	dav1dThreadFrames = dav1dThreadFrames.coerceIn(0, 16),
)

enum class LibVLCDeblocking(
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
