package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.mpvAudioChannels
import org.jellyfin.androidtv.preference.mpvAudioOutput
import org.jellyfin.androidtv.preference.mpvAudioPitchCorrection
import org.jellyfin.androidtv.preference.mpvAudioSpdif
import org.jellyfin.androidtv.preference.mpvDeband
import org.jellyfin.androidtv.preference.mpvDecoder
import org.jellyfin.androidtv.preference.mpvDecoderThreads
import org.jellyfin.androidtv.preference.mpvDeinterlace
import org.jellyfin.androidtv.preference.mpvFrameDrop
import org.jellyfin.androidtv.preference.mpvGpuApi
import org.jellyfin.androidtv.preference.mpvGpuContext
import org.jellyfin.androidtv.preference.mpvInterpolation
import org.jellyfin.androidtv.preference.mpvLoopFilter
import org.jellyfin.androidtv.preference.mpvOptionOverrides
import org.jellyfin.androidtv.preference.mpvReplayGain
import org.jellyfin.androidtv.preference.mpvScaler
import org.jellyfin.androidtv.preference.mpvSoftwareDecodingForLiveTv
import org.jellyfin.androidtv.preference.mpvSubtitleAssOverride
import org.jellyfin.androidtv.preference.mpvSubtitleUseMargins
import org.jellyfin.androidtv.preference.mpvToneMapping
import org.jellyfin.androidtv.preference.mpvVideoOutput
import org.jellyfin.androidtv.preference.mpvVideoSync
import org.jellyfin.playback.mpv.LibMPVPlaybackOptions
import org.jellyfin.playback.mpv.LibMPVVideoDecoder
import org.jellyfin.playback.mpv.isLibMPVOptionManagedByJellyfin
import org.jellyfin.playback.mpv.parseLibMPVOptionOverrides
import org.jellyfin.preference.PreferenceEnum

interface LibMPVPreferenceOption : PreferenceEnum {
	val descriptionRes: Int
	val mpvValue: String
}

enum class LibMPVDecoder(
	override val nameRes: Int,
	override val descriptionRes: Int,
	val decoder: LibMPVVideoDecoder,
) : LibMPVPreferenceOption {
	AUTOMATIC(R.string.preference_mpv_decoder_auto_unsafe, R.string.preference_mpv_decoder_auto_unsafe_description, LibMPVVideoDecoder.AUTOMATIC),
	AUTO_SAFE(R.string.preference_mpv_decoder_auto_safe, R.string.preference_mpv_decoder_auto_safe_description, LibMPVVideoDecoder.AUTO_SAFE),
	SOFTWARE(R.string.preference_mpv_decoder_software, R.string.preference_mpv_decoder_software_description, LibMPVVideoDecoder.SOFTWARE),
	MEDIACODEC(R.string.preference_mpv_decoder_mediacodec, R.string.preference_mpv_decoder_mediacodec_description, LibMPVVideoDecoder.MEDIACODEC),
	MEDIACODEC_COPY(R.string.preference_mpv_decoder_mediacodec_copy, R.string.preference_mpv_decoder_mediacodec_copy_description, LibMPVVideoDecoder.MEDIACODEC_COPY),
	;
	override val mpvValue get() = decoder.mpvValue
}

enum class LibMPVVideoOutput(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	GPU(R.string.preference_mpv_video_output_gpu, R.string.preference_mpv_video_output_gpu_description, "gpu"),
	GPU_NEXT(R.string.preference_mpv_video_output_gpu_next, R.string.preference_mpv_video_output_gpu_next_description, "gpu-next"),
}

enum class LibMPVGpuContext(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	ANDROID(R.string.preference_mpv_gpu_context_android, R.string.preference_mpv_gpu_context_android_description, "android"),
	AUTO(R.string.preference_mpv_value_auto, R.string.preference_mpv_gpu_context_auto_description, "auto"),
}

enum class LibMPVGpuApi(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	AUTO(R.string.preference_mpv_value_auto, R.string.preference_mpv_gpu_api_auto_description, "auto"),
	OPENGL(R.string.preference_mpv_gpu_api_opengl, R.string.preference_mpv_gpu_api_opengl_description, "opengl"),
	VULKAN(R.string.preference_mpv_gpu_api_vulkan, R.string.preference_mpv_gpu_api_vulkan_description, "vulkan"),
}

enum class LibMPVVideoSync(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	AUDIO(R.string.preference_mpv_video_sync_audio, R.string.preference_mpv_video_sync_audio_description, "audio"),
	DISPLAY_RESAMPLE(R.string.preference_mpv_video_sync_display_resample, R.string.preference_mpv_video_sync_display_resample_description, "display-resample"),
	DISPLAY_VDROP(R.string.preference_mpv_video_sync_display_vdrop, R.string.preference_mpv_video_sync_display_vdrop_description, "display-vdrop"),
	DISPLAY_ADROP(R.string.preference_mpv_video_sync_display_adrop, R.string.preference_mpv_video_sync_display_adrop_description, "display-adrop"),
	DISPLAY_DESYNC(R.string.preference_mpv_video_sync_display_desync, R.string.preference_mpv_video_sync_display_desync_description, "display-desync"),
	DESYNC(R.string.preference_mpv_video_sync_desync, R.string.preference_mpv_video_sync_desync_description, "desync"),
}

enum class LibMPVFrameDrop(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	VIDEO_OUTPUT(R.string.preference_mpv_framedrop_vo, R.string.preference_mpv_framedrop_vo_description, "vo"),
	DISABLED(R.string.preference_mpv_value_disabled, R.string.preference_mpv_framedrop_no_description, "no"),
	DECODER(R.string.preference_mpv_framedrop_decoder, R.string.preference_mpv_framedrop_decoder_description, "decoder"),
	DECODER_AND_VIDEO_OUTPUT(R.string.preference_mpv_framedrop_decoder_vo, R.string.preference_mpv_framedrop_decoder_vo_description, "decoder+vo"),
}

enum class LibMPVDeinterlace(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	AUTO(R.string.preference_mpv_value_auto, R.string.preference_mpv_deinterlace_auto_description, "auto"),
	ENABLED(R.string.preference_mpv_value_enabled, R.string.preference_mpv_deinterlace_enabled_description, "yes"),
	DISABLED(R.string.preference_mpv_value_disabled, R.string.preference_mpv_deinterlace_disabled_description, "no"),
}

enum class LibMPVScaler(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	BILINEAR(R.string.preference_mpv_scaler_bilinear, R.string.preference_mpv_scaler_bilinear_description, "bilinear"),
	BICUBIC_FAST(R.string.preference_mpv_scaler_bicubic_fast, R.string.preference_mpv_scaler_bicubic_fast_description, "bicubic_fast"),
	SPLINE36(R.string.preference_mpv_scaler_spline36, R.string.preference_mpv_scaler_spline36_description, "spline36"),
	LANCZOS(R.string.preference_mpv_scaler_lanczos, R.string.preference_mpv_scaler_lanczos_description, "lanczos"),
	EWA_LANCZOSSHARP(R.string.preference_mpv_scaler_ewa_lanczossharp, R.string.preference_mpv_scaler_ewa_lanczossharp_description, "ewa_lanczossharp"),
}

enum class LibMPVToneMapping(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	AUTO(R.string.preference_mpv_value_auto, R.string.preference_mpv_tone_mapping_auto_description, "auto"),
	CLIP(R.string.preference_mpv_tone_mapping_clip, R.string.preference_mpv_tone_mapping_clip_description, "clip"),
	MOBIUS(R.string.preference_mpv_tone_mapping_mobius, R.string.preference_mpv_tone_mapping_mobius_description, "mobius"),
	REINHARD(R.string.preference_mpv_tone_mapping_reinhard, R.string.preference_mpv_tone_mapping_reinhard_description, "reinhard"),
	HABLE(R.string.preference_mpv_tone_mapping_hable, R.string.preference_mpv_tone_mapping_hable_description, "hable"),
	BT_2390(R.string.preference_mpv_tone_mapping_bt2390, R.string.preference_mpv_tone_mapping_bt2390_description, "bt.2390"),
	BT_2446A(R.string.preference_mpv_tone_mapping_bt2446a, R.string.preference_mpv_tone_mapping_bt2446a_description, "bt.2446a"),
	SPLINE(R.string.preference_mpv_tone_mapping_spline, R.string.preference_mpv_tone_mapping_spline_description, "spline"),
}

enum class LibMPVAudioOutput(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	AUTO(R.string.preference_mpv_value_auto, R.string.preference_mpv_audio_output_auto_description, ""),
	AUDIOTRACK(R.string.preference_mpv_audio_output_audiotrack, R.string.preference_mpv_audio_output_audiotrack_description, "audiotrack"),
	OPENSLES(R.string.preference_mpv_audio_output_opensles, R.string.preference_mpv_audio_output_opensles_description, "opensles"),
	NULL(R.string.preference_mpv_audio_output_null, R.string.preference_mpv_audio_output_null_description, "null"),
}

enum class LibMPVAudioChannels(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	AUTO_SAFE(R.string.preference_mpv_audio_channels_auto_safe, R.string.preference_mpv_audio_channels_auto_safe_description, "auto-safe"),
	AUTO(R.string.preference_mpv_value_auto, R.string.preference_mpv_audio_channels_auto_description, "auto"),
	STEREO(R.string.preference_mpv_audio_channels_stereo, R.string.preference_mpv_audio_channels_stereo_description, "stereo"),
	SURROUND_5_1(R.string.preference_mpv_audio_channels_5_1, R.string.preference_mpv_audio_channels_5_1_description, "5.1"),
	SURROUND_7_1(R.string.preference_mpv_audio_channels_7_1, R.string.preference_mpv_audio_channels_7_1_description, "7.1"),
}

enum class LibMPVAudioSpdif(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	NONE(R.string.preference_mpv_value_disabled, R.string.preference_mpv_audio_spdif_none_description, ""),
	AC3(R.string.preference_mpv_audio_spdif_ac3, R.string.preference_mpv_audio_spdif_ac3_description, "ac3"),
	AC3_EAC3(R.string.preference_mpv_audio_spdif_ac3_eac3, R.string.preference_mpv_audio_spdif_ac3_eac3_description, "ac3,eac3"),
	AC3_EAC3_DTS(R.string.preference_mpv_audio_spdif_ac3_eac3_dts, R.string.preference_mpv_audio_spdif_ac3_eac3_dts_description, "ac3,eac3,dts"),
	ALL(R.string.preference_mpv_audio_spdif_all, R.string.preference_mpv_audio_spdif_all_description, "ac3,eac3,dts,dts-hd,truehd"),
}

enum class LibMPVReplayGain(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	DISABLED(R.string.preference_mpv_value_disabled, R.string.preference_mpv_replay_gain_disabled_description, "no"),
	TRACK(R.string.preference_mpv_replay_gain_track, R.string.preference_mpv_replay_gain_track_description, "track"),
	ALBUM(R.string.preference_mpv_replay_gain_album, R.string.preference_mpv_replay_gain_album_description, "album"),
}

enum class LibMPVLoopFilter(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	DEFAULT(R.string.preference_mpv_value_default, R.string.preference_mpv_loop_filter_default_description, "default"),
	NONE(R.string.preference_mpv_loop_filter_none, R.string.preference_mpv_loop_filter_none_description, "none"),
	NONREF(R.string.preference_mpv_loop_filter_nonref, R.string.preference_mpv_loop_filter_nonref_description, "nonref"),
	BIDIR(R.string.preference_mpv_loop_filter_bidir, R.string.preference_mpv_loop_filter_bidir_description, "bidir"),
	NONKEY(R.string.preference_mpv_loop_filter_nonkey, R.string.preference_mpv_loop_filter_nonkey_description, "nonkey"),
	ALL(R.string.preference_mpv_loop_filter_all, R.string.preference_mpv_loop_filter_all_description, "all"),
}

enum class LibMPVSubtitleAssOverride(override val nameRes: Int, override val descriptionRes: Int, override val mpvValue: String) : LibMPVPreferenceOption {
	NO(R.string.preference_mpv_sub_ass_override_no, R.string.preference_mpv_sub_ass_override_no_description, "no"),
	YES(R.string.preference_mpv_sub_ass_override_yes, R.string.preference_mpv_sub_ass_override_yes_description, "yes"),
	SCALE(R.string.preference_mpv_sub_ass_override_scale, R.string.preference_mpv_sub_ass_override_scale_description, "scale"),
	FORCE(R.string.preference_mpv_sub_ass_override_force, R.string.preference_mpv_sub_ass_override_force_description, "force"),
	STRIP(R.string.preference_mpv_sub_ass_override_strip, R.string.preference_mpv_sub_ass_override_strip_description, "strip"),
}

enum class LibMPVChoiceSetting(
	val slug: String,
	val titleRes: Int,
	val descriptionRes: Int,
	val optionNames: Set<String>,
) {
	DECODER("decoder", R.string.preference_mpv_decoder, R.string.preference_mpv_decoder_description, setOf("hwdec")),
	VIDEO_OUTPUT("video-output", R.string.preference_mpv_video_output, R.string.preference_mpv_video_output_description, setOf("vo")),
	GPU_CONTEXT("gpu-context", R.string.preference_mpv_gpu_context, R.string.preference_mpv_gpu_context_description, setOf("gpu-context")),
	GPU_API("gpu-api", R.string.preference_mpv_gpu_api, R.string.preference_mpv_gpu_api_description, setOf("gpu-api")),
	VIDEO_SYNC("video-sync", R.string.preference_mpv_video_sync, R.string.preference_mpv_video_sync_description, setOf("video-sync")),
	FRAME_DROP("framedrop", R.string.preference_mpv_framedrop, R.string.preference_mpv_framedrop_description, setOf("framedrop")),
	DEINTERLACE("deinterlace", R.string.preference_mpv_deinterlace, R.string.preference_mpv_deinterlace_description, setOf("deinterlace")),
	SCALER("scaler", R.string.preference_mpv_scaler, R.string.preference_mpv_scaler_description, setOf("scale", "cscale", "dscale")),
	TONE_MAPPING("tone-mapping", R.string.preference_mpv_tone_mapping, R.string.preference_mpv_tone_mapping_description, setOf("tone-mapping")),
	AUDIO_OUTPUT("audio-output", R.string.preference_mpv_audio_output, R.string.preference_mpv_audio_output_description, setOf("ao")),
	AUDIO_CHANNELS("audio-channels", R.string.preference_mpv_audio_channels, R.string.preference_mpv_audio_channels_description, setOf("audio-channels")),
	AUDIO_SPDIF("audio-spdif", R.string.preference_mpv_audio_spdif, R.string.preference_mpv_audio_spdif_description, setOf("audio-spdif")),
	REPLAY_GAIN("replay-gain", R.string.preference_mpv_replay_gain, R.string.preference_mpv_replay_gain_description, setOf("replaygain")),
	LOOP_FILTER("loop-filter", R.string.preference_mpv_loop_filter, R.string.preference_mpv_loop_filter_description, setOf("vd-lavc-skiploopfilter")),
	SUBTITLE_ASS_OVERRIDE("sub-ass-override", R.string.preference_mpv_sub_ass_override, R.string.preference_mpv_sub_ass_override_description, setOf("sub-ass-override")),
	;

	fun options(): List<LibMPVPreferenceOption> = when (this) {
		DECODER -> LibMPVDecoder.entries
		VIDEO_OUTPUT -> LibMPVVideoOutput.entries
		GPU_CONTEXT -> LibMPVGpuContext.entries
		GPU_API -> LibMPVGpuApi.entries
		VIDEO_SYNC -> LibMPVVideoSync.entries
		FRAME_DROP -> LibMPVFrameDrop.entries
		DEINTERLACE -> LibMPVDeinterlace.entries
		SCALER -> LibMPVScaler.entries
		TONE_MAPPING -> LibMPVToneMapping.entries
		AUDIO_OUTPUT -> LibMPVAudioOutput.entries
		AUDIO_CHANNELS -> LibMPVAudioChannels.entries
		AUDIO_SPDIF -> LibMPVAudioSpdif.entries
		REPLAY_GAIN -> LibMPVReplayGain.entries
		LOOP_FILTER -> LibMPVLoopFilter.entries
		SUBTITLE_ASS_OVERRIDE -> LibMPVSubtitleAssOverride.entries
	}

	fun defaultOption(): LibMPVPreferenceOption = when (this) {
		DECODER -> LibMPVDecoder.AUTOMATIC
		VIDEO_OUTPUT -> LibMPVVideoOutput.GPU_NEXT
		GPU_CONTEXT -> LibMPVGpuContext.ANDROID
		GPU_API -> LibMPVGpuApi.AUTO
		VIDEO_SYNC -> LibMPVVideoSync.AUDIO
		FRAME_DROP -> LibMPVFrameDrop.VIDEO_OUTPUT
		DEINTERLACE -> LibMPVDeinterlace.DISABLED
		SCALER -> LibMPVScaler.BILINEAR
		TONE_MAPPING -> LibMPVToneMapping.AUTO
		AUDIO_OUTPUT -> LibMPVAudioOutput.AUTO
		AUDIO_CHANNELS -> LibMPVAudioChannels.AUTO_SAFE
		AUDIO_SPDIF -> LibMPVAudioSpdif.NONE
		REPLAY_GAIN -> LibMPVReplayGain.DISABLED
		LOOP_FILTER -> LibMPVLoopFilter.DEFAULT
		SUBTITLE_ASS_OVERRIDE -> LibMPVSubtitleAssOverride.NO
	}

	fun selected(preferences: UserPreferences): LibMPVPreferenceOption = when (this) {
		DECODER -> preferences[UserPreferences.mpvDecoder]
		VIDEO_OUTPUT -> preferences[UserPreferences.mpvVideoOutput]
		GPU_CONTEXT -> preferences[UserPreferences.mpvGpuContext]
		GPU_API -> preferences[UserPreferences.mpvGpuApi]
		VIDEO_SYNC -> preferences[UserPreferences.mpvVideoSync]
		FRAME_DROP -> preferences[UserPreferences.mpvFrameDrop]
		DEINTERLACE -> preferences[UserPreferences.mpvDeinterlace]
		SCALER -> preferences[UserPreferences.mpvScaler]
		TONE_MAPPING -> preferences[UserPreferences.mpvToneMapping]
		AUDIO_OUTPUT -> preferences[UserPreferences.mpvAudioOutput]
		AUDIO_CHANNELS -> preferences[UserPreferences.mpvAudioChannels]
		AUDIO_SPDIF -> preferences[UserPreferences.mpvAudioSpdif]
		REPLAY_GAIN -> preferences[UserPreferences.mpvReplayGain]
		LOOP_FILTER -> preferences[UserPreferences.mpvLoopFilter]
		SUBTITLE_ASS_OVERRIDE -> preferences[UserPreferences.mpvSubtitleAssOverride]
	}

	@Suppress("UNCHECKED_CAST")
	fun select(preferences: UserPreferences, option: LibMPVPreferenceOption) = when (this) {
		DECODER -> preferences[UserPreferences.mpvDecoder] = option as LibMPVDecoder
		VIDEO_OUTPUT -> preferences[UserPreferences.mpvVideoOutput] = option as LibMPVVideoOutput
		GPU_CONTEXT -> preferences[UserPreferences.mpvGpuContext] = option as LibMPVGpuContext
		GPU_API -> preferences[UserPreferences.mpvGpuApi] = option as LibMPVGpuApi
		VIDEO_SYNC -> preferences[UserPreferences.mpvVideoSync] = option as LibMPVVideoSync
		FRAME_DROP -> preferences[UserPreferences.mpvFrameDrop] = option as LibMPVFrameDrop
		DEINTERLACE -> preferences[UserPreferences.mpvDeinterlace] = option as LibMPVDeinterlace
		SCALER -> preferences[UserPreferences.mpvScaler] = option as LibMPVScaler
		TONE_MAPPING -> preferences[UserPreferences.mpvToneMapping] = option as LibMPVToneMapping
		AUDIO_OUTPUT -> preferences[UserPreferences.mpvAudioOutput] = option as LibMPVAudioOutput
		AUDIO_CHANNELS -> preferences[UserPreferences.mpvAudioChannels] = option as LibMPVAudioChannels
		AUDIO_SPDIF -> preferences[UserPreferences.mpvAudioSpdif] = option as LibMPVAudioSpdif
		REPLAY_GAIN -> preferences[UserPreferences.mpvReplayGain] = option as LibMPVReplayGain
		LOOP_FILTER -> preferences[UserPreferences.mpvLoopFilter] = option as LibMPVLoopFilter
		SUBTITLE_ASS_OVERRIDE -> preferences[UserPreferences.mpvSubtitleAssOverride] = option as LibMPVSubtitleAssOverride
	}

	companion object {
		fun fromSlug(value: String?) = entries.firstOrNull { setting -> setting.slug == value }
	}
}

fun UserPreferences.mpvPlaybackOptions() = LibMPVPlaybackOptions(
	videoOutput = this[UserPreferences.mpvVideoOutput].mpvValue,
	gpuContext = this[UserPreferences.mpvGpuContext].mpvValue,
	gpuApi = this[UserPreferences.mpvGpuApi].mpvValue,
	videoSync = this[UserPreferences.mpvVideoSync].mpvValue,
	frameDrop = this[UserPreferences.mpvFrameDrop].mpvValue,
	deinterlace = this[UserPreferences.mpvDeinterlace].mpvValue,
	interpolation = this[UserPreferences.mpvInterpolation],
	scaler = this[UserPreferences.mpvScaler].mpvValue,
	deband = this[UserPreferences.mpvDeband],
	toneMapping = this[UserPreferences.mpvToneMapping].mpvValue,
	audioOutput = this[UserPreferences.mpvAudioOutput].mpvValue,
	audioChannels = this[UserPreferences.mpvAudioChannels].mpvValue,
	audioSpdif = this[UserPreferences.mpvAudioSpdif].mpvValue,
	audioPitchCorrection = this[UserPreferences.mpvAudioPitchCorrection],
	replayGain = this[UserPreferences.mpvReplayGain].mpvValue,
	decoderThreads = this[UserPreferences.mpvDecoderThreads].coerceIn(0, 32),
	skipLoopFilter = this[UserPreferences.mpvLoopFilter].mpvValue,
	subtitleAssOverride = this[UserPreferences.mpvSubtitleAssOverride].mpvValue,
	subtitleUseMargins = this[UserPreferences.mpvSubtitleUseMargins],
	softwareDecodingForLiveTv = this[UserPreferences.mpvSoftwareDecodingForLiveTv],
	customOptions = parseLibMPVOptionOverrides(this[UserPreferences.mpvOptionOverrides]).values
		.filterKeys { name -> !isLibMPVOptionManagedByJellyfin(name) },
)

fun UserPreferences.resetLibMPVPreferences() {
	this[UserPreferences.mpvDecoder] = LibMPVDecoder.AUTOMATIC
	this[UserPreferences.mpvVideoOutput] = LibMPVVideoOutput.GPU_NEXT
	this[UserPreferences.mpvGpuContext] = LibMPVGpuContext.ANDROID
	this[UserPreferences.mpvGpuApi] = LibMPVGpuApi.AUTO
	this[UserPreferences.mpvVideoSync] = LibMPVVideoSync.AUDIO
	this[UserPreferences.mpvFrameDrop] = LibMPVFrameDrop.VIDEO_OUTPUT
	this[UserPreferences.mpvDeinterlace] = LibMPVDeinterlace.DISABLED
	this[UserPreferences.mpvScaler] = LibMPVScaler.BILINEAR
	this[UserPreferences.mpvToneMapping] = LibMPVToneMapping.AUTO
	this[UserPreferences.mpvAudioOutput] = LibMPVAudioOutput.AUTO
	this[UserPreferences.mpvAudioChannels] = LibMPVAudioChannels.AUTO_SAFE
	this[UserPreferences.mpvAudioSpdif] = LibMPVAudioSpdif.NONE
	this[UserPreferences.mpvReplayGain] = LibMPVReplayGain.DISABLED
	this[UserPreferences.mpvLoopFilter] = LibMPVLoopFilter.DEFAULT
	this[UserPreferences.mpvSubtitleAssOverride] = LibMPVSubtitleAssOverride.NO
	this[UserPreferences.mpvInterpolation] = false
	this[UserPreferences.mpvDeband] = false
	this[UserPreferences.mpvAudioPitchCorrection] = true
	this[UserPreferences.mpvSubtitleUseMargins] = true
	this[UserPreferences.mpvSoftwareDecodingForLiveTv] = false
	this[UserPreferences.mpvDecoderThreads] = 0
	this[UserPreferences.mpvOptionOverrides] = ""
}
