package org.jellyfin.playback.media3.exoplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import timber.log.Timber

@OptIn(UnstableApi::class)
class ExternalSubtitleMediaSourceFactory(
	private val delegate: MediaSource.Factory,
	private val dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory {
	private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

	override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
		delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
		return this
	}

	override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
		this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
		delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
		return this
	}

	override fun getSupportedTypes(): IntArray = delegate.supportedTypes

	override fun createMediaSource(mediaItem: MediaItem): MediaSource {
		val localConfiguration = mediaItem.localConfiguration ?: return delegate.createMediaSource(mediaItem)
		val subtitleConfigurations = localConfiguration.subtitleConfigurations
		if (subtitleConfigurations.isEmpty()) return delegate.createMediaSource(mediaItem)

		Timber.i(
			"Routing %d external subtitle source(s) through renderer path mediaId=%s uri=%s",
			subtitleConfigurations.size,
			mediaItem.mediaId,
			localConfiguration.uri.safeForLogging(),
		)

		val mediaItemWithoutExternalSubtitles = mediaItem.buildUpon()
			.setSubtitleConfigurations(emptyList())
			.build()

		val mediaSources = buildList {
			add(delegate.createMediaSource(mediaItemWithoutExternalSubtitles))
			subtitleConfigurations.mapIndexedTo(this) { index, subtitleConfiguration ->
				Timber.d(
					"Creating external subtitle renderer source mediaId=%s sourceIndex=%d id=%s mime=%s language=%s label=%s uri=%s",
					mediaItem.mediaId,
					index + 1,
					subtitleConfiguration.id ?: "none",
					subtitleConfiguration.mimeType ?: "unknown",
					subtitleConfiguration.language ?: "unknown",
					subtitleConfiguration.label ?: "none",
					subtitleConfiguration.uri.safeForLogging(),
				)

				@Suppress("DEPRECATION")
				val subtitleSourceFactory = SingleSampleMediaSource.Factory(dataSourceFactory)
				loadErrorHandlingPolicy?.let(subtitleSourceFactory::setLoadErrorHandlingPolicy)
				subtitleSourceFactory.createMediaSource(subtitleConfiguration, C.TIME_UNSET)
			}
		}

		Timber.d(
			"Merging main media source with %d external subtitle renderer source(s) mediaId=%s",
			subtitleConfigurations.size,
			mediaItem.mediaId,
		)

		return MergingMediaSource(*mediaSources.toTypedArray())
	}

	private fun Uri.safeForLogging(): String = buildUpon()
		.clearQuery()
		.fragment(null)
		.build()
		.toString()
}
