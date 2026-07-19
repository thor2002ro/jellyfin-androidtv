package org.jellyfin.androidtv.preference

import org.jellyfin.preference.booleanPreference

private val preferExoPlayerFfmpegPreference = booleanPreference("exoplayer_prefer_ffmpeg", defaultValue = true)
private val preferExoPlayerFfmpegVideoPreference = booleanPreference("exoplayer_prefer_ffmpeg_video", defaultValue = false)
private val preferExoPlayerFfmpegVideoForLiveTvPreference = booleanPreference("exoplayer_prefer_ffmpeg_video_livetv", defaultValue = false)
private val preferExoPlayerFfmpegAudioForLiveTvPreference = booleanPreference("exoplayer_prefer_ffmpeg_audio_livetv", defaultValue = false)

/** Whether ExoPlayer should prefer FFmpeg audio renderers to core ones. */
val UserPreferences.Companion.preferExoPlayerFfmpeg get() = preferExoPlayerFfmpegPreference

/** Whether ExoPlayer should prefer FFmpeg video renderers to core ones. */
val UserPreferences.Companion.preferExoPlayerFfmpegVideo get() = preferExoPlayerFfmpegVideoPreference

/** Whether ExoPlayer should prefer the FFmpeg video renderer for Live TV. */
val UserPreferences.Companion.preferExoPlayerFfmpegVideoForLiveTv get() = preferExoPlayerFfmpegVideoForLiveTvPreference

/** Whether ExoPlayer should prefer the FFmpeg audio renderer for Live TV. */
val UserPreferences.Companion.preferExoPlayerFfmpegAudioForLiveTv get() = preferExoPlayerFfmpegAudioForLiveTvPreference
