package org.jellyfin.playback.exoplayer.dovi

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@UnstableApi
internal class DoviMediaSourceFactory(
	private val progressiveFactory: (DoviTransformContext?) -> MediaSource.Factory,
	private val hlsFactory: (DoviTransformContext?) -> MediaSource.Factory,
	private val context: (MediaItem) -> DoviTransformContext?,
) : MediaSource.Factory {
	private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
	private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

	override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
		drmSessionManagerProvider = provider
	}

	override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
		loadErrorHandlingPolicy = policy
	}

	override fun getSupportedTypes(): IntArray = progressiveFactory(null).supportedTypes

	override fun createMediaSource(mediaItem: MediaItem): MediaSource {
		val local = mediaItem.localConfiguration
		val contentType = local?.let { Util.inferContentTypeForUriAndMimeType(it.uri, it.mimeType) }
		val sourceContext = context(mediaItem)
		val factory = if (contentType == C.CONTENT_TYPE_HLS) {
			hlsFactory(sourceContext)
		} else {
			progressiveFactory(sourceContext)
		}
		drmSessionManagerProvider?.let(factory::setDrmSessionManagerProvider)
		loadErrorHandlingPolicy?.let(factory::setLoadErrorHandlingPolicy)
		return factory.createMediaSource(mediaItem)
	}
}
