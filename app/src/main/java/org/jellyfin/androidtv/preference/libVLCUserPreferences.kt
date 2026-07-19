package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.preference.constant.LibVLCAudioOutput
import org.jellyfin.androidtv.preference.constant.LibVLCDeblocking
import org.jellyfin.androidtv.preference.constant.LibVLCDecoder
import org.jellyfin.androidtv.preference.constant.LibVLCReplayGainMode
import org.jellyfin.androidtv.preference.constant.LibVLCVideoOutput
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.floatPreference
import org.jellyfin.preference.intPreference

private val libVLCDecoderPreference = enumPreference("libvlc_decoder", LibVLCDecoder.DISABLED)
private val libVLCVideoOutputPreference = enumPreference("libvlc_video_output", LibVLCVideoOutput.AUTOMATIC)
private val libVLCAudioOutputPreference = enumPreference("libvlc_audio_output", LibVLCAudioOutput.AAUDIO)
private val libVLCReplayGainPreference = booleanPreference("libvlc_replay_gain", false)
private val libVLCReplayGainModePreference = enumPreference("libvlc_replay_gain_mode", LibVLCReplayGainMode.TRACK)
private val libVLCReplayGainPreampPreference = floatPreference("libvlc_replay_gain_preamp", 0f)
private val libVLCReplayGainDefaultPreference = floatPreference("libvlc_replay_gain_default", -7f)
private val libVLCReplayGainPeakProtectionPreference = booleanPreference("libvlc_replay_gain_peak_protection", true)
private val libVLCDeblockingPreference = enumPreference("libvlc_deblocking", LibVLCDeblocking.AUTOMATIC)
private val libVLCFrameSkipPreference = booleanPreference("libvlc_frame_skip", false)
private val libVLCAudioTimeStretchPreference = booleanPreference("libvlc_audio_time_stretch", false)
private val libVLCDav1dThreadFramesPreference = intPreference("libvlc_dav1d_thread_frames", 0)

/** LibVLC video decoder mode. */
val UserPreferences.Companion.libVLCDecoder get() = libVLCDecoderPreference

/** LibVLC video output mode. */
val UserPreferences.Companion.libVLCVideoOutput get() = libVLCVideoOutputPreference

/** LibVLC audio output mode. */
val UserPreferences.Companion.libVLCAudioOutput get() = libVLCAudioOutputPreference

/** LibVLC audio replay gain. */
val UserPreferences.Companion.libVLCReplayGain get() = libVLCReplayGainPreference

/** LibVLC audio replay gain mode. */
val UserPreferences.Companion.libVLCReplayGainMode get() = libVLCReplayGainModePreference

/** LibVLC audio replay gain preamp in dB. */
val UserPreferences.Companion.libVLCReplayGainPreamp get() = libVLCReplayGainPreampPreference

/** LibVLC audio replay gain fallback in dB. */
val UserPreferences.Companion.libVLCReplayGainDefault get() = libVLCReplayGainDefaultPreference

/** LibVLC audio replay gain peak protection. */
val UserPreferences.Companion.libVLCReplayGainPeakProtection get() = libVLCReplayGainPeakProtectionPreference

/** LibVLC avcodec skip loop filter mode. */
val UserPreferences.Companion.libVLCDeblocking get() = libVLCDeblockingPreference

/** LibVLC frame skip. */
val UserPreferences.Companion.libVLCFrameSkip get() = libVLCFrameSkipPreference

/** LibVLC audio time stretching. */
val UserPreferences.Companion.libVLCAudioTimeStretch get() = libVLCAudioTimeStretchPreference

/** LibVLC dav1d frame thread override. 0 lets dav1d choose. */
val UserPreferences.Companion.libVLCDav1dThreadFrames get() = libVLCDav1dThreadFramesPreference
