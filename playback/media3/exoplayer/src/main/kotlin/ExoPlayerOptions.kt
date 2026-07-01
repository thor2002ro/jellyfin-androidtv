package org.jellyfin.playback.media3.exoplayer

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlin.time.Duration

data class ExoPlayerOptions(
	val preferFfmpeg: Boolean = false,
	val enableDebugLogging: Boolean = false,
	val enableLibass: Boolean = false,
	val libassRenderType: AssRenderType = AssRenderType.OVERLAY_OPEN_GL,
	val libassGlyphSize: Int = 20_000,
	val libassCacheSize: Int = 256,
	val libassMaxRenderPixels: Int = 0,
	val parseSubtitlesDuringExtraction: Boolean = true,
	val baseDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
	val minBufferDuration: Duration? = null,
	val maxBufferDuration: Duration? = null,
	val bufferForPlaybackDuration: Duration? = null,
	val bufferForPlaybackAfterRebufferDuration: Duration? = null,
	val liveTvBufferDuration: Duration? = null,
)
