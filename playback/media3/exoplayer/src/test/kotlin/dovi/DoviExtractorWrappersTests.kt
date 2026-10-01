package org.jellyfin.playback.exoplayer.dovi

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.exoplayer.source.MediaSource
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviTarget
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.github.thor2002ro.libdovi.DoviTransformResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk

class DoviExtractorWrappersTests : FunSpec({
	test("context-null ordinary extractor forwards endTracks exactly once") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { null }, passthroughTransformer())
		wrapper.init(delegate)
		extractor.output!!.track(1, C.TRACK_TYPE_VIDEO).format(
			Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
		)

		extractor.output!!.endTracks()
		extractor.output!!.endTracks()

		delegate.createdTrackIds shouldBe listOf(1)
		delegate.endTracksCount shouldBe 1
	}

	test("audio and text only HLS chunk forwards endTracks exactly once") {
		val chunk = RecordingHlsChunk()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviHlsMediaChunkExtractor(chunk, { context() }, passthroughTransformer())
		wrapper.init(delegate)
		chunk.output!!.track(1, C.TRACK_TYPE_AUDIO)
		chunk.output!!.track(2, C.TRACK_TYPE_TEXT)

		chunk.output!!.endTracks()
		chunk.output!!.endTracks()

		delegate.createdTrackIds shouldBe listOf(1, 2)
		delegate.endTracksCount shouldBe 1
	}

	test("progressive extractor wraps only video track outputs") {
		val extractor = RecordingExtractor()
		val wrapper = DoviExtractor(extractor, { context() }, passthroughTransformer())
		val output = RecordingExtractorOutput()
		wrapper.init(output)

		val video = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		extractor.output!!.track(2, C.TRACK_TYPE_AUDIO).shouldBeInstanceOf<RecordingTrackOutput>()
		output.createdTrackIds shouldBe listOf(2)
		video.format(doviFormat())
		extractor.output!!.endTracks()
		output.createdTrackIds shouldBe listOf(2, 1)
	}

	test("progressive seek drops a partial sample before the next access unit") {
		val extractor = RecordingExtractor()
		val transformed = mutableListOf<List<Byte>>()
		val wrapper = DoviExtractor(
			delegate = extractor,
			context = { context() },
			transformer = DoviSampleTransformer { sample, _ ->
				transformed += sample.bytes.toList()
				DoviTransformResult(sample.bytes, DoviPresentation.PROFILE_8_1, emptySet(), DoviPresentation.PROFILE_7_FEL)
			},
		)
		wrapper.init(RecordingExtractorOutput())
		val track = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		track.format(doviFormat())
		extractor.output!!.endTracks()
		track.sampleData(ParsableByteArray(byteArrayOf(1, 2)), 2, TrackOutput.SAMPLE_DATA_PART_MAIN)

		wrapper.seek(100, 1000)
		track.sampleData(ParsableByteArray(byteArrayOf(3, 4)), 2, TrackOutput.SAMPLE_DATA_PART_MAIN)
		track.sampleMetadata(1000, 0, 2, 0, null)

		transformed shouldBe listOf(listOf<Byte>(3, 4))
		extractor.seekCount shouldBe 1
	}

	test("single-track TS prepares after endTracks when its Format arrives late") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { context() }, passthroughTransformer())
		wrapper.init(delegate)
		val video = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		extractor.output!!.endTracks()
		delegate.createdTrackIds shouldBe emptyList()

		video.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build())

		delegate.createdTrackIds shouldBe listOf(1)
	}

	test("multiple TS video tracks without an authoritative relation fail closed") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(delegate)
		val first = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val second = extractor.output!!.track(2, C.TRACK_TYPE_VIDEO)
		extractor.output!!.endTracks()
		first.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build())

		shouldThrow<DoviSampleTransformationException> {
			second.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build())
		}
		delegate.createdTrackIds shouldBe emptyList()
	}

	test("HLS recreation keeps the item-scoped request and output wrapper") {
		val chunk = RecordingHlsChunk()
		val fixedContext = context(DoviTarget.MEL)
		val wrapper = DoviHlsMediaChunkExtractor(chunk, { fixedContext }, passthroughTransformer())
		val output = RecordingExtractorOutput()
		wrapper.init(output)
		val track = chunk.output!!.track(1, C.TRACK_TYPE_VIDEO)
		track.format(doviFormat())
		chunk.output!!.endTracks()
		output.createdTrackIds shouldBe listOf(1)

		val recreated = wrapper.recreate()
		recreated.shouldBeInstanceOf<DoviHlsMediaChunkExtractor>()
	}

	test("truncated HLS segment clears partial bytes before the next segment") {
		val chunk = RecordingHlsChunk()
		val transformed = mutableListOf<List<Byte>>()
		val wrapper = DoviHlsMediaChunkExtractor(
			delegate = chunk,
			context = { context() },
			transformer = DoviSampleTransformer { sample, _ ->
				transformed += sample.bytes.toList()
				DoviTransformResult(sample.bytes, DoviPresentation.PROFILE_8_1, emptySet(), DoviPresentation.PROFILE_7_FEL)
			},
		)
		wrapper.init(RecordingExtractorOutput())
		val track = chunk.output!!.track(1, C.TRACK_TYPE_VIDEO)
		track.format(doviFormat())
		chunk.output!!.endTracks()
		track.sampleData(ParsableByteArray(byteArrayOf(1)), 1, TrackOutput.SAMPLE_DATA_PART_MAIN)

		wrapper.onTruncatedSegmentParsed()
		track.sampleData(ParsableByteArray(byteArrayOf(2)), 1, TrackOutput.SAMPLE_DATA_PART_MAIN)
		track.sampleMetadata(0, 0, 1, 0, null)

		transformed.single() shouldContainExactly listOf<Byte>(2)
		chunk.truncatedCount shouldBe 1
	}

	test("truncated HLS segment abandons an unmatched layered timestamp") {
		val chunk = RecordingHlsChunk()
		val wrapper = DoviHlsMediaChunkExtractor(
			chunk,
			{ context(input = DoviPresentation.PROFILE_7_FEL) },
			passthroughTransformer(),
		)
		val delegate = RecordingExtractorOutput()
		wrapper.init(delegate)
		val base = chunk.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val dependent = chunk.output!!.track(2, C.TRACK_TYPE_VIDEO)
		base.format(doviFormat(trackId = 10))
		dependent.format(doviFormat(trackId = 20, baseTrackId = 10))
		chunk.output!!.endTracks()
		base.emit(annexBNal(type = 1, layer = 0, payload = 1), 10)

		wrapper.onTruncatedSegmentParsed()
		base.emit(annexBNal(type = 1, layer = 0, payload = 2), 20)
		dependent.emit(annexBNal(type = 62, layer = 0, payload = 3), 20)

		delegate.recording(1).metadata.map { it.timeUs } shouldBe listOf(20L)
	}

	test("dual-track Profile 7 pairs timestamps and emits only transformed base output") {
		val extractor = RecordingExtractor()
		var transformed: ByteArray? = null
		var supplementalRpu: ByteArray? = null
		val wrapper = DoviExtractor(
			delegate = extractor,
			context = { context(input = DoviPresentation.PROFILE_7_FEL) },
			transformer = DoviSampleTransformer { sample, _ ->
				transformed = sample.bytes
				supplementalRpu = sample.supplementalRpu
				DoviTransformResult(sample.bytes, DoviPresentation.PROFILE_8_1, emptySet(), DoviPresentation.PROFILE_7_FEL)
			},
		)
		val delegate = RecordingExtractorOutput()
		wrapper.init(delegate)
		val base = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val enhancement = extractor.output!!.track(2, C.TRACK_TYPE_VIDEO)
		base.format(doviFormat(trackId = 10))
		enhancement.format(
			doviFormat(trackId = 20, baseTrackId = 10).buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build()
		)
		extractor.output!!.endTracks()
		val baseBytes = annexBNal(type = 1, layer = 0, payload = 0x50)
		val rpu = annexBNal(type = 62, layer = 0, payload = 0x2a)
		val enhancementBytes = rpu + annexBNal(type = 63, layer = 0, payload = 0x30)
		enhancement.sampleData(ParsableByteArray(enhancementBytes), enhancementBytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
		enhancement.sampleMetadata(1_000, 0, enhancementBytes.size, 0, null)
		base.sampleData(ParsableByteArray(baseBytes), baseBytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
		base.sampleMetadata(1_000, C.BUFFER_FLAG_KEY_FRAME, baseBytes.size, 0, null)

		transformed?.toList() shouldBe baseBytes.toList()
		supplementalRpu?.toList() shouldBe rpu.drop(4)
		delegate.recording(1).metadata.size shouldBe 1
		delegate.createdTrackIds shouldBe listOf(1)
	}

	test("dual-track preparation fails before creating a delegate track when CSD roles are ambiguous") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(delegate)
		extractor.output!!.track(1, C.TRACK_TYPE_VIDEO).format(doviFormat())
		extractor.output!!.track(2, C.TRACK_TYPE_VIDEO).format(doviFormat())

		shouldThrow<DoviSampleTransformationException> { extractor.output!!.endTracks() }
		delegate.createdTrackIds shouldBe emptyList()
	}

	test("self-referential vdep fails before delegate creation") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(delegate)
		extractor.output!!.track(1, C.TRACK_TYPE_VIDEO).format(doviFormat(trackId = 10, baseTrackId = 10))
		extractor.output!!.track(2, C.TRACK_TYPE_VIDEO).format(doviFormat(trackId = 20))

		shouldThrow<DoviSampleTransformationException> { extractor.output!!.endTracks() }
		delegate.createdTrackIds shouldBe emptyList()
	}

	test("malformed prebuilt dependency metadata fails before delegate creation") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(delegate)
		val malformed = MdtaMetadataEntry(
			DOVI_DEPENDENCY_KEY,
			byteArrayOf('D'.code.toByte(), 'V'.code.toByte(), 'D'.code.toByte(), 1),
			MdtaMetadataEntry.TYPE_INDICATOR_RESERVED,
		)
		extractor.output!!.track(1, C.TRACK_TYPE_VIDEO).format(
			doviFormat().buildUpon().setMetadata(Metadata(malformed)).build(),
		)
		extractor.output!!.track(2, C.TRACK_TYPE_VIDEO).format(doviFormat(trackId = 20, baseTrackId = 10))

		shouldThrow<DoviSampleTransformationException> { extractor.output!!.endTracks() }
		delegate.createdTrackIds shouldBe emptyList()
	}

	test("duplicate prebuilt dependency metadata fails before delegate creation") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(delegate)
		val duplicate = doviDependencyEntry(trackId = 10, baseTrackId = C.INDEX_UNSET)
		extractor.output!!.track(1, C.TRACK_TYPE_VIDEO).format(
			doviFormat().buildUpon().setMetadata(Metadata(duplicate, duplicate)).build(),
		)
		extractor.output!!.track(2, C.TRACK_TYPE_VIDEO).format(doviFormat(trackId = 20, baseTrackId = 10))

		shouldThrow<DoviSampleTransformationException> { extractor.output!!.endTracks() }
		delegate.createdTrackIds shouldBe emptyList()
	}

	test("a referenced base that also declares a dependency fails before delegate creation") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(delegate)
		extractor.output!!.track(1, C.TRACK_TYPE_VIDEO).format(doviFormat(trackId = 10, baseTrackId = 30))
		extractor.output!!.track(2, C.TRACK_TYPE_VIDEO).format(doviFormat(trackId = 20, baseTrackId = 10))

		shouldThrow<DoviSampleTransformationException> { extractor.output!!.endTracks() }
		delegate.createdTrackIds shouldBe emptyList()
	}

	test("layered pairing never emits a later completed timestamp before the oldest pending timestamp") {
		val extractor = RecordingExtractor()
		val delegate = RecordingExtractorOutput()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(delegate)
		val base = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val dependent = extractor.output!!.track(2, C.TRACK_TYPE_VIDEO)
		base.format(doviFormat(trackId = 10))
		dependent.format(doviFormat(trackId = 20, baseTrackId = 10).buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build())
		extractor.output!!.endTracks()
		val baseBytes = annexBNal(type = 1, layer = 0, payload = 0x50)
		val dependentBytes = annexBNal(type = 62, layer = 0, payload = 0x2a) + annexBNal(type = 63, layer = 0, payload = 0x30)

		base.emit(baseBytes, 100)
		base.emit(baseBytes, 200)
		dependent.emit(dependentBytes, 200)
		delegate.recording(1).metadata shouldBe emptyList()
		dependent.emit(dependentBytes, 100)
		delegate.recording(1).metadata.map { it.timeUs } shouldBe listOf(100L, 200L)
	}

	test("dual-track pairing is bounded and fails closed when timestamps do not match") {
		val extractor = RecordingExtractor()
		val wrapper = DoviExtractor(
			extractor,
			{ context(input = DoviPresentation.PROFILE_7_FEL) },
			passthroughTransformer(),
		)
		wrapper.init(RecordingExtractorOutput())
		val base = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val enhancement = extractor.output!!.track(2, C.TRACK_TYPE_VIDEO)
		base.format(doviFormat(trackId = 10))
		enhancement.format(
			doviFormat(trackId = 20, baseTrackId = 10).buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build()
		)
		extractor.output!!.endTracks()
		val bytes = annexBNal(type = 1, layer = 0, payload = 0x50)
		val error = shouldThrow<DoviSampleTransformationException> {
			repeat(9) { index ->
				base.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
				base.sampleMetadata(index.toLong(), 0, bytes.size, 0, null)
			}
		}
		error.status shouldBe io.github.thor2002ro.libdovi.DoviStatus.INCONSISTENT_RPU
	}

	test("dual-track pairing also bounds retained sample bytes") {
		val extractor = RecordingExtractor()
		val wrapper = DoviExtractor(
			extractor,
			{ context(input = DoviPresentation.PROFILE_7_FEL) },
			passthroughTransformer(),
		)
		wrapper.init(RecordingExtractorOutput())
		val base = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val dependent = extractor.output!!.track(2, C.TRACK_TYPE_VIDEO)
		base.format(doviFormat(trackId = 10))
		dependent.format(doviFormat(trackId = 20, baseTrackId = 10).buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).build())
		extractor.output!!.endTracks()
		val oversized = ByteArray(16 * 1024 * 1024 + 1)

		shouldThrow<DoviSampleTransformationException> { base.emit(oversized, 0) }.status shouldBe
			io.github.thor2002ro.libdovi.DoviStatus.INCONSISTENT_RPU
	}

	test("extractor EOF reports a pending unmatched layered timestamp") {
		val extractor = RecordingExtractor()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(RecordingExtractorOutput())
		val base = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val dependent = extractor.output!!.track(2, C.TRACK_TYPE_VIDEO)
		base.format(doviFormat(trackId = 10))
		dependent.format(doviFormat(trackId = 20, baseTrackId = 10))
		extractor.output!!.endTracks()
		base.emit(annexBNal(type = 1, layer = 0, payload = 1), 10)

		shouldThrow<DoviSampleTransformationException> {
			wrapper.read(mockk(relaxed = true), PositionHolder())
		}.status shouldBe io.github.thor2002ro.libdovi.DoviStatus.INCONSISTENT_RPU
	}

	test("selected MP4 relation is revalidated on Format refresh") {
		val extractor = RecordingExtractor()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(RecordingExtractorOutput())
		val base = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val dependent = extractor.output!!.track(2, C.TRACK_TYPE_VIDEO)
		base.format(doviFormat(trackId = 10))
		dependent.format(doviFormat(trackId = 20, baseTrackId = 10))
		extractor.output!!.endTracks()

		shouldThrow<DoviSampleTransformationException> {
			dependent.format(doviFormat(trackId = 20, baseTrackId = 11))
		}
	}

	test("layered input rejects an in-band base RPU when the dependent supplies one") {
		val extractor = RecordingExtractor()
		val wrapper = DoviExtractor(extractor, { context(input = DoviPresentation.PROFILE_7_FEL) }, passthroughTransformer())
		wrapper.init(RecordingExtractorOutput())
		val base = extractor.output!!.track(1, C.TRACK_TYPE_VIDEO)
		val dependent = extractor.output!!.track(2, C.TRACK_TYPE_VIDEO)
		base.format(doviFormat(trackId = 10))
		dependent.format(doviFormat(trackId = 20, baseTrackId = 10))
		extractor.output!!.endTracks()
		base.emit(annexBNal(type = 62, layer = 0, payload = 1), 10)

		shouldThrow<DoviSampleTransformationException> {
			dependent.emit(annexBNal(type = 62, layer = 0, payload = 2), 10)
		}
	}

	test("media source creation captures same-URI contexts independently and survives redirect URIs") {
		val first = context(DoviTarget.MEL, DoviPresentation.PROFILE_7_FEL)
		val second = context(DoviTarget.PROFILE_8_1, DoviPresentation.PROFILE_5)
		val captured = mutableListOf<DoviTransformContext?>()
		fun recordingFactory(context: DoviTransformContext?): MediaSource.Factory {
			captured += context
			return mockk(relaxed = true) {
				every { createMediaSource(any()) } returns mockk(relaxed = true)
			}
		}
		val factory = DoviMediaSourceFactory(
			progressiveFactory = ::recordingFactory,
			hlsFactory = ::recordingFactory,
			context = { item -> item.localConfiguration?.tag as? DoviTransformContext },
		)
		val uri = mockk<Uri>(relaxed = true)
		factory.createMediaSource(MediaItem.Builder().setUri(uri).setTag(first).build())
		factory.createMediaSource(MediaItem.Builder().setUri(uri).setTag(second).build())

		captured shouldBe listOf(first, second)
	}

	test("extractor creation at a distinct final redirect URI retains the requested source context") {
		val fixedContext = context(DoviTarget.MEL, DoviPresentation.PROFILE_7_FEL)
		val delegate = RecordingExtractorsFactory()
		var transformedRequest: DoviTransformRequest? = null
		val factory = DoviExtractorsFactory(
			delegate,
			fixedContext,
			DoviSampleTransformer { sample, request ->
				transformedRequest = request
				DoviTransformResult(sample.bytes, DoviPresentation.PROFILE_7_MEL, emptySet(), DoviPresentation.PROFILE_7_FEL)
			},
		)
		val finalUri = mockk<Uri>(relaxed = true)

		val extractor = factory.createExtractors(finalUri, mapOf("X-Final" to listOf("true"))).single()
		val output = RecordingExtractorOutput()
		extractor.init(output)
		val track = delegate.created.single().output!!.track(1, C.TRACK_TYPE_VIDEO)
		track.format(doviFormat())
		delegate.created.single().output!!.endTracks()
		track.emit(annexBNal(type = 1, layer = 0, payload = 1), 0)

		delegate.lastUri shouldBe finalUri
		transformedRequest shouldBe fixedContext.request
	}
})

private fun context(
	target: DoviTarget = DoviTarget.PROFILE_8_1,
	input: DoviPresentation = DoviPresentation.PROFILE_8_1,
) =
	DoviTransformContext(
		request = DoviTransformRequest(target),
		sourceBasePresentation = DoviPresentation.HDR10,
		dvLevel = 6,
		pairEnhancementTrack = input == DoviPresentation.PROFILE_7_FEL,
	)

private fun annexBNal(type: Int, layer: Int, payload: Int) = byteArrayOf(
	0, 0, 0, 1,
	((type shl 1) or ((layer ushr 5) and 1)).toByte(),
	(((layer and 0x1f) shl 3) or 1).toByte(),
	payload.toByte(),
)

private fun TrackOutput.emit(bytes: ByteArray, timeUs: Long) {
	sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
	sampleMetadata(timeUs, 0, bytes.size, 0, null)
}

private fun passthroughTransformer() = DoviSampleTransformer { sample, _ ->
	DoviTransformResult(sample.bytes, DoviPresentation.PROFILE_8_1, emptySet(), DoviPresentation.PROFILE_7_FEL)
}

private fun doviFormat(trackId: Int? = null, baseTrackId: Int = C.INDEX_UNSET) = Format.Builder()
	.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
	.setCodecs("dvhe.07.06")
	.apply {
		if (trackId != null) setMetadata(Metadata(doviDependencyEntry(trackId, baseTrackId)))
	}
	.build()

private fun doviDependencyEntry(trackId: Int, baseTrackId: Int) = MdtaMetadataEntry(
	DOVI_DEPENDENCY_KEY,
	byteArrayOf('D'.code.toByte(), 'V'.code.toByte(), 'D'.code.toByte(), 1) +
		trackId.bigEndianBytes() + baseTrackId.bigEndianBytes(),
	MdtaMetadataEntry.TYPE_INDICATOR_RESERVED,
)

private fun Int.bigEndianBytes() = byteArrayOf(
	(this ushr 24).toByte(),
	(this ushr 16).toByte(),
	(this ushr 8).toByte(),
	toByte(),
)

private const val DOVI_DEPENDENCY_KEY = "com.jellyfin.androidtv.dovi.track-dependency"

private class RecordingExtractor : Extractor {
	var output: ExtractorOutput? = null
	var seekCount = 0
	override fun sniff(input: ExtractorInput) = true
	override fun init(output: ExtractorOutput) { this.output = output }
	override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
	override fun seek(position: Long, timeUs: Long) { seekCount++ }
	override fun release() = Unit
}

private class RecordingExtractorsFactory : ExtractorsFactory {
	var lastUri: Uri? = null
	val created = mutableListOf<RecordingExtractor>()
	override fun createExtractors(): Array<Extractor> = arrayOf(RecordingExtractor().also(created::add))
	override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
		lastUri = uri
		return createExtractors()
	}
}

private class RecordingHlsChunk : HlsMediaChunkExtractor {
	var output: ExtractorOutput? = null
	var truncatedCount = 0
	override fun init(extractorOutput: ExtractorOutput) { output = extractorOutput }
	override fun read(extractorInput: ExtractorInput) = false
	override fun isPackedAudioExtractor() = false
	override fun isReusable() = false
	override fun recreate(): HlsMediaChunkExtractor = RecordingHlsChunk()
	override fun onTruncatedSegmentParsed() { truncatedCount++ }
}

private class RecordingExtractorOutput : ExtractorOutput {
	private val tracks = mutableMapOf<Int, RecordingTrackOutput>()
	val createdTrackIds = mutableListOf<Int>()
	var endTracksCount = 0
	override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
		createdTrackIds += id
		RecordingTrackOutput()
	}
	override fun endTracks() { endTracksCount++ }
	override fun seekMap(seekMap: SeekMap) = Unit
	fun recording(id: Int): RecordingTrackOutput = requireNotNull(tracks[id])
}
