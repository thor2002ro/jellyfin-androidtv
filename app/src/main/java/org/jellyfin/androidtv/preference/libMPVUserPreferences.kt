package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.preference.constant.LibMPVAudioChannels
import org.jellyfin.androidtv.preference.constant.LibMPVAudioOutput
import org.jellyfin.androidtv.preference.constant.LibMPVAudioPresetOption
import org.jellyfin.androidtv.preference.constant.LibMPVAudioSpdif
import org.jellyfin.androidtv.preference.constant.LibMPVDecoder
import org.jellyfin.androidtv.preference.constant.LibMPVDeinterlace
import org.jellyfin.androidtv.preference.constant.LibMPVFrameDrop
import org.jellyfin.androidtv.preference.constant.LibMPVGpuApi
import org.jellyfin.androidtv.preference.constant.LibMPVGpuContext
import org.jellyfin.androidtv.preference.constant.LibMPVLoopFilter
import org.jellyfin.androidtv.preference.constant.LibMPVReplayGain
import org.jellyfin.androidtv.preference.constant.LibMPVScaler
import org.jellyfin.androidtv.preference.constant.LibMPVSubtitleAssOverride
import org.jellyfin.androidtv.preference.constant.LibMPVToneMapping
import org.jellyfin.androidtv.preference.constant.LibMPVVideoOutput
import org.jellyfin.androidtv.preference.constant.LibMPVVideoPresetOption
import org.jellyfin.androidtv.preference.constant.LibMPVVideoSync
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.intPreference
import org.jellyfin.preference.stringPreference

private val mpvDecoderPreference = enumPreference("mpv_decoder", LibMPVDecoder.AUTOMATIC)
private val mpvVideoOutputPreference = enumPreference("mpv_video_output", LibMPVVideoOutput.GPU_NEXT)
private val mpvGpuContextPreference = enumPreference("mpv_gpu_context", LibMPVGpuContext.ANDROID)
private val mpvGpuApiPreference = enumPreference("mpv_gpu_api", LibMPVGpuApi.AUTO)
private val mpvVideoSyncPreference = enumPreference("mpv_video_sync", LibMPVVideoSync.AUDIO)
private val mpvFrameDropPreference = enumPreference("mpv_framedrop", LibMPVFrameDrop.VIDEO_OUTPUT)
private val mpvDeinterlacePreference = enumPreference("mpv_deinterlace", LibMPVDeinterlace.DISABLED)
private val mpvScalerPreference = enumPreference("mpv_scaler", LibMPVScaler.BILINEAR)
private val mpvToneMappingPreference = enumPreference("mpv_tone_mapping", LibMPVToneMapping.AUTO)
private val mpvAudioOutputPreference = enumPreference("mpv_audio_output", LibMPVAudioOutput.AUTO)
private val mpvAudioChannelsPreference = enumPreference("mpv_audio_channels", LibMPVAudioChannels.AUTO_SAFE)
private val mpvAudioSpdifPreference = enumPreference("mpv_audio_spdif", LibMPVAudioSpdif.NONE)
private val mpvReplayGainPreference = enumPreference("mpv_replay_gain", LibMPVReplayGain.DISABLED)
private val mpvLoopFilterPreference = enumPreference("mpv_loop_filter", LibMPVLoopFilter.DEFAULT)
private val mpvSubtitleAssOverridePreference = enumPreference("mpv_sub_ass_override", LibMPVSubtitleAssOverride.NO)
private val mpvInterpolationPreference = booleanPreference("mpv_interpolation", false)
private val mpvDebandPreference = booleanPreference("mpv_deband", false)
private val mpvAudioPitchCorrectionPreference = booleanPreference("mpv_audio_pitch_correction", true)
private val mpvSubtitleUseMarginsPreference = booleanPreference("mpv_sub_use_margins", true)
private val mpvSoftwareDecodingForLiveTvPreference = booleanPreference("mpv_software_decoding_livetv", false)
private val mpvNvidiaShieldWorkaroundsPreference = booleanPreference("mpv_nvidia_shield_workarounds", true)
private val mpvVideoPresetPreference = enumPreference("mpv_anime_preset_level", LibMPVVideoPresetOption.OFF)
private val mpvAudioPresetPreference = enumPreference("mpv_audio_preset", LibMPVAudioPresetOption.OFF)
private val mpvDecoderThreadsPreference = intPreference("mpv_decoder_threads", 0)
private val mpvOptionOverridesPreference = stringPreference("mpv_option_overrides", "")

val UserPreferences.Companion.mpvDecoder get() = mpvDecoderPreference
val UserPreferences.Companion.mpvVideoOutput get() = mpvVideoOutputPreference
val UserPreferences.Companion.mpvGpuContext get() = mpvGpuContextPreference
val UserPreferences.Companion.mpvGpuApi get() = mpvGpuApiPreference
val UserPreferences.Companion.mpvVideoSync get() = mpvVideoSyncPreference
val UserPreferences.Companion.mpvFrameDrop get() = mpvFrameDropPreference
val UserPreferences.Companion.mpvDeinterlace get() = mpvDeinterlacePreference
val UserPreferences.Companion.mpvScaler get() = mpvScalerPreference
val UserPreferences.Companion.mpvToneMapping get() = mpvToneMappingPreference
val UserPreferences.Companion.mpvAudioOutput get() = mpvAudioOutputPreference
val UserPreferences.Companion.mpvAudioChannels get() = mpvAudioChannelsPreference
val UserPreferences.Companion.mpvAudioSpdif get() = mpvAudioSpdifPreference
val UserPreferences.Companion.mpvReplayGain get() = mpvReplayGainPreference
val UserPreferences.Companion.mpvLoopFilter get() = mpvLoopFilterPreference
val UserPreferences.Companion.mpvSubtitleAssOverride get() = mpvSubtitleAssOverridePreference
val UserPreferences.Companion.mpvInterpolation get() = mpvInterpolationPreference
val UserPreferences.Companion.mpvDeband get() = mpvDebandPreference
val UserPreferences.Companion.mpvAudioPitchCorrection get() = mpvAudioPitchCorrectionPreference
val UserPreferences.Companion.mpvSubtitleUseMargins get() = mpvSubtitleUseMarginsPreference
val UserPreferences.Companion.mpvSoftwareDecodingForLiveTv get() = mpvSoftwareDecodingForLiveTvPreference
val UserPreferences.Companion.mpvNvidiaShieldWorkarounds get() = mpvNvidiaShieldWorkaroundsPreference
val UserPreferences.Companion.mpvVideoPreset get() = mpvVideoPresetPreference
val UserPreferences.Companion.mpvAudioPreset get() = mpvAudioPresetPreference
val UserPreferences.Companion.mpvDecoderThreads get() = mpvDecoderThreadsPreference
val UserPreferences.Companion.mpvOptionOverrides get() = mpvOptionOverridesPreference
