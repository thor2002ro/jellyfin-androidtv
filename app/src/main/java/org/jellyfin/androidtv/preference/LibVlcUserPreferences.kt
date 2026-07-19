package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.preference.constant.LibVlcAudioOutput
import org.jellyfin.androidtv.preference.constant.LibVlcDeblocking
import org.jellyfin.androidtv.preference.constant.LibVlcDecoder
import org.jellyfin.androidtv.preference.constant.LibVlcReplayGainMode
import org.jellyfin.androidtv.preference.constant.LibVlcVideoOutput
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.floatPreference
import org.jellyfin.preference.intPreference

private val libVlcDecoderPreference = enumPreference("libvlc_decoder", LibVlcDecoder.DISABLED)
private val libVlcVideoOutputPreference = enumPreference("libvlc_video_output", LibVlcVideoOutput.AUTOMATIC)
private val libVlcAudioOutputPreference = enumPreference("libvlc_audio_output", LibVlcAudioOutput.AAUDIO)
private val libVlcReplayGainPreference = booleanPreference("libvlc_replay_gain", false)
private val libVlcReplayGainModePreference = enumPreference("libvlc_replay_gain_mode", LibVlcReplayGainMode.TRACK)
private val libVlcReplayGainPreampPreference = floatPreference("libvlc_replay_gain_preamp", 0f)
private val libVlcReplayGainDefaultPreference = floatPreference("libvlc_replay_gain_default", -7f)
private val libVlcReplayGainPeakProtectionPreference = booleanPreference("libvlc_replay_gain_peak_protection", true)
private val libVlcDeblockingPreference = enumPreference("libvlc_deblocking", LibVlcDeblocking.AUTOMATIC)
private val libVlcFrameSkipPreference = booleanPreference("libvlc_frame_skip", false)
private val libVlcAudioTimeStretchPreference = booleanPreference("libvlc_audio_time_stretch", false)
private val libVlcDav1dThreadFramesPreference = intPreference("libvlc_dav1d_thread_frames", 0)

/** LibVLC video decoder mode. */
val UserPreferences.Companion.libVlcDecoder get() = libVlcDecoderPreference

/** LibVLC video output mode. */
val UserPreferences.Companion.libVlcVideoOutput get() = libVlcVideoOutputPreference

/** LibVLC audio output mode. */
val UserPreferences.Companion.libVlcAudioOutput get() = libVlcAudioOutputPreference

/** LibVLC audio replay gain. */
val UserPreferences.Companion.libVlcReplayGain get() = libVlcReplayGainPreference

/** LibVLC audio replay gain mode. */
val UserPreferences.Companion.libVlcReplayGainMode get() = libVlcReplayGainModePreference

/** LibVLC audio replay gain preamp in dB. */
val UserPreferences.Companion.libVlcReplayGainPreamp get() = libVlcReplayGainPreampPreference

/** LibVLC audio replay gain fallback in dB. */
val UserPreferences.Companion.libVlcReplayGainDefault get() = libVlcReplayGainDefaultPreference

/** LibVLC audio replay gain peak protection. */
val UserPreferences.Companion.libVlcReplayGainPeakProtection get() = libVlcReplayGainPeakProtectionPreference

/** LibVLC avcodec skip loop filter mode. */
val UserPreferences.Companion.libVlcDeblocking get() = libVlcDeblockingPreference

/** LibVLC frame skip. */
val UserPreferences.Companion.libVlcFrameSkip get() = libVlcFrameSkipPreference

/** LibVLC audio time stretching. */
val UserPreferences.Companion.libVlcAudioTimeStretch get() = libVlcAudioTimeStretchPreference

/** LibVLC dav1d frame thread override. 0 lets dav1d choose. */
val UserPreferences.Companion.libVlcDav1dThreadFrames get() = libVlcDav1dThreadFramesPreference
