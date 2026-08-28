package org.jellyfin.playback.exoplayer.dovi

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.github.thor2002ro.libdovi.DoviTransformObservation
import io.github.thor2002ro.libdovi.DoviTransformStrategy
import org.jellyfin.playback.core.model.PlaybackDoviTransformProcessor

internal data class DoviTransformContext(
	val request: DoviTransformRequest,
	val sourceBasePresentation: DoviPresentation,
	val dvLevel: Int? = null,
	val pairEnhancementTrack: Boolean,
	val transformStrategy: DoviTransformStrategy = DoviTransformStrategy.LIBDOVI,
	val onTransformObserved: (DoviTransformObservation) -> Unit = {},
	val onProcessorChanged: (PlaybackDoviTransformProcessor) -> Unit = {},
)

@UnstableApi
internal class DoviExtractorsFactory(
	private val delegate: ExtractorsFactory,
	private val context: DoviTransformContext?,
	private val transformer: DoviSampleTransformer? = null,
) : ExtractorsFactory {
	override fun createExtractors(): Array<Extractor> = delegate.createExtractors().wrapped()

	override fun createExtractors(
		uri: Uri,
		responseHeaders: Map<String, List<String>>,
	): Array<Extractor> = delegate.createExtractors(uri, responseHeaders).wrapped()

	@Suppress("DEPRECATION")
	override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory = apply {
		delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
	}

	override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
		delegate.setSubtitleParserFactory(subtitleParserFactory)
	}

	override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory = apply {
		delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
	}

	override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply {
		delegate.setParseHagcMetadata(parseHagcMetadata)
	}

	private fun Array<Extractor>.wrapped(): Array<Extractor> {
		return map { extractor -> DoviExtractor(extractor, { context }, transformer) }.toTypedArray()
	}
}

@UnstableApi
internal class DoviHlsExtractorFactory(
	private val delegate: HlsExtractorFactory,
	private val context: DoviTransformContext?,
	private val transformer: DoviSampleTransformer? = null,
) : HlsExtractorFactory {
	override fun createExtractor(
		uri: Uri,
		format: Format,
		muxedCaptionFormats: List<Format>?,
		timestampAdjuster: TimestampAdjuster,
		responseHeaders: Map<String, List<String>>,
		sniffingExtractorInput: ExtractorInput,
		playerId: PlayerId,
	): HlsMediaChunkExtractor = DoviHlsMediaChunkExtractor(
		delegate.createExtractor(
			uri,
			format,
			muxedCaptionFormats,
			timestampAdjuster,
			responseHeaders,
			sniffingExtractorInput,
			playerId,
		),
		{ context },
		transformer,
	)

	override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory = apply {
		delegate.setSubtitleParserFactory(subtitleParserFactory)
	}

	@Suppress("DEPRECATION")
	override fun experimentalParseSubtitlesDuringExtraction(enabled: Boolean): HlsExtractorFactory = apply {
		delegate.experimentalParseSubtitlesDuringExtraction(enabled)
	}

	override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): HlsExtractorFactory = apply {
		delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
	}

	override fun getOutputTextFormat(sourceFormat: Format): Format = delegate.getOutputTextFormat(sourceFormat)
}
