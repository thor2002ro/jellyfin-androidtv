package org.jellyfin.playback.exoplayer.dovi

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import io.github.thor2002ro.libdovi.DoviFraming
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviRepair
import io.github.thor2002ro.libdovi.DoviStatus
import io.github.thor2002ro.libdovi.DoviTarget
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.github.thor2002ro.libdovi.DoviTransformResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.media3.exoplayer.doviTransformationPlaybackErrorCode
import java.io.ByteArrayOutputStream

class DoviTrackOutputTests : FunSpec({
	test("Profile 5 to Profile 8.1 uses the exact request and negotiated base evidence") {
		val delegate = RecordingTrackOutput()
		val request = DoviTransformRequest(DoviTarget.PROFILE_8_1)
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { request },
			sourceBasePresentation = { DoviPresentation.HDR10 },
			transformer = DoviSampleTransformer { sample, actualRequest ->
				actualRequest shouldBe request
				sample.framing shouldBe DoviFraming.ANNEX_B
				sample.nalLengthSize shouldBe 0
				sample.sourceBasePresentation shouldBe DoviPresentation.HDR10
				result(sample.bytes + 0x7f.toByte(), DoviPresentation.PROFILE_8_1)
			},
		)
		output.format(doviFormat(profile = 5))
		output.emit(byteArrayOf(1, 2), timeUs = 10)

		delegate.format?.sampleMimeType shouldBe MimeTypes.VIDEO_DOLBY_VISION
		delegate.format?.codecs shouldBe "dvhe.08.06"
		delegate.bytes.toByteArray().toList() shouldContainExactly listOf<Byte>(1, 2, 0x7f)
		delegate.metadata.single().size shouldBe 3
	}

	test("FEL to MEL and repair flags are passed without rewriting policy") {
		val delegate = RecordingTrackOutput()
		val repairs = setOf(DoviRepair.REMOVE_MAPPING, DoviRepair.ZERO_ACTIVE_AREA)
		val request = DoviTransformRequest(DoviTarget.MEL, repairs)
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { request },
			sourceBasePresentation = { DoviPresentation.HDR10_PLUS },
			transformer = DoviSampleTransformer { sample, actualRequest ->
				actualRequest shouldBe request
				sample.sourceBasePresentation shouldBe DoviPresentation.HDR10_PLUS
				result(sample.bytes, DoviPresentation.PROFILE_7_MEL, repairs)
			},
		)
		output.format(doviFormat(profile = 7, prefix = "dvh1"))
		output.emit(byteArrayOf(3, 4))

		delegate.format?.codecs shouldBe "dvh1.07.06"
	}

	test("first successful transform publishes libdovi input and output once") {
		val observations = mutableListOf<Pair<DoviPresentation, DoviPresentation>>()
		val output = DoviTrackOutput(
			delegate = RecordingTrackOutput(),
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			inputPresentation = { DoviPresentation.UNKNOWN },
			onTransformObserved = { observation -> observations += observation.input to observation.output },
			transformer = DoviSampleTransformer { sample, _ ->
				result(
					bytes = sample.bytes,
					input = DoviPresentation.PROFILE_7_FEL,
					output = DoviPresentation.PROFILE_8_1,
				)
			},
		)
		output.format(doviFormat(profile = 7))

		output.emit(byteArrayOf(1))
		output.emit(byteArrayOf(2))

		observations shouldBe listOf(DoviPresentation.PROFILE_7_FEL to DoviPresentation.PROFILE_8_1)
	}

	test("source-base output signals plain HEVC and removes only DV initialization data") {
		val delegate = RecordingTrackOutput()
		val hevcInitialization = byteArrayOf(0, 0, 0, 1, 0x40)
		val doviInitialization = byteArrayOf(1, 0, (7 shl 1).toByte(), 0, 0)
		val request = DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION)
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { request },
			sourceBasePresentation = { DoviPresentation.HLG },
			transformer = DoviSampleTransformer { sample, _ ->
				result(sample.bytes, DoviPresentation.HLG)
			},
		)
		output.format(
			doviFormat(profile = 8).buildUpon()
				.setCodecs("dvhe.08.06,hvc1.2.4.L153.B0")
				.setInitializationData(listOf(hevcInitialization, doviInitialization))
				.setColorInfo(
					ColorInfo.Builder()
						.setColorSpace(C.COLOR_SPACE_BT2020)
						.setColorTransfer(C.COLOR_TRANSFER_ST2084)
						.setColorRange(C.COLOR_RANGE_LIMITED)
						.setHdrStaticInfo(byteArrayOf(1, 2, 3))
						.build()
				)
				.build()
		)
		output.emit(byteArrayOf(5, 6))

		delegate.format?.sampleMimeType shouldBe MimeTypes.VIDEO_H265
		delegate.format?.codecs shouldBe "hvc1.2.4.L153.B0"
		delegate.format?.colorInfo?.colorTransfer shouldBe C.COLOR_TRANSFER_HLG
		delegate.format?.colorInfo?.hdrStaticInfo shouldBe null
		delegate.format?.initializationData?.map { data -> data.toList() } shouldBe listOf(hevcInitialization.toList())
	}

	test("native Profile 8.4 result dynamically signals Dolby Vision Profile 8") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_4) },
			sourceBasePresentation = { DoviPresentation.HLG },
			transformer = DoviSampleTransformer { sample, _ -> result(sample.bytes, DoviPresentation.PROFILE_8_4) },
		)
		output.format(doviFormat(profile = 8, prefix = "dvh1"))
		output.emit(byteArrayOf(7, 8))

		delegate.format?.codecs shouldBe "dvh1.08.06"
	}

	test("same presentation forwards a changed source format fingerprint") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			transformer = DoviSampleTransformer { sample, _ -> result(sample.bytes, DoviPresentation.PROFILE_8_1) },
		)
		output.format(doviFormat(profile = 7).buildUpon().setWidth(1920).setHeight(1080).build())
		output.emit(byteArrayOf(1))
		output.format(doviFormat(profile = 7).buildUpon().setWidth(3840).setHeight(2160).build())
		output.emit(byteArrayOf(2))

		delegate.formats.map { format -> format.width } shouldBe listOf(1920, 3840)
		delegate.formats.map { format -> format.codecs } shouldBe listOf("dvhe.08.06", "dvhe.08.06")
	}

	test("Dolby Vision output fails closed when source level signaling is unavailable") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			transformer = DoviSampleTransformer { sample, _ -> result(sample.bytes, DoviPresentation.PROFILE_8_1) },
		)
		output.format(
			Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.07").build()
		)
		val error = shouldThrow<DoviSampleTransformationException> { output.emit(byteArrayOf(1, 2)) }
		error.status shouldBe DoviStatus.INTERNAL_ERROR
		delegate.format shouldBe null
		delegate.bytes.size() shouldBe 0
	}

	test("TS Dolby Vision output uses the exact negotiated level") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			dvLevel = { 9 },
			transformer = DoviSampleTransformer { sample, _ -> result(sample.bytes, DoviPresentation.PROFILE_8_1) },
		)
		output.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build())
		output.emit(byteArrayOf(1, 2))

		delegate.format?.codecs shouldBe "dvhe.08.09"
	}

	test("source-base codec is derived from actual H265Reader-style SPS initialization data") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.SOURCE_BASE_PRESENTATION) },
			sourceBasePresentation = { DoviPresentation.HDR10 },
			transformer = DoviSampleTransformer { sample, _ -> result(sample.bytes, DoviPresentation.HDR10) },
		)
		output.format(
			doviFormat(profile = 7).buildUpon()
				.setInitializationData(listOf(byteArrayOf(0, 0, 1) + H265_SPS_FIXTURE))
				.build()
		)
		output.emit(byteArrayOf(1, 2))

		delegate.format?.codecs shouldBe "hvc1.2.4.L153.B0"
	}

	test("native output that disagrees with the request fails before format or bytes") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			transformer = DoviSampleTransformer { sample, _ -> result(sample.bytes, DoviPresentation.PROFILE_7_MEL) },
		)
		output.format(doviFormat(profile = 7))

		shouldThrow<DoviSampleTransformationException> { output.emit(byteArrayOf(1, 2)) }
		delegate.format shouldBe null
		delegate.bytes.size() shouldBe 0
	}

	test("Matroska supplemental prefix is removed and raw RPU is passed separately") {
		val delegate = RecordingTrackOutput()
		var capturedSupplemental: ByteArray? = null
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			sourceBasePresentation = { DoviPresentation.HDR10 },
			transformer = DoviSampleTransformer { sample, _ ->
				capturedSupplemental = sample.supplementalRpu
				result(sample.bytes, DoviPresentation.PROFILE_8_1)
			},
		)
		output.format(doviFormat(profile = 7))
		output.sampleData(
			ParsableByteArray(byteArrayOf(0, 0, 0, 2)),
			4,
			TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
		)
		output.sampleData(ParsableByteArray(byteArrayOf(1, 2)), 2, TrackOutput.SAMPLE_DATA_PART_MAIN)
		output.sampleData(
			ParsableByteArray(byteArrayOf(3, 4)),
			2,
			TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
		)
		output.sampleMetadata(10, C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA, 8, 0, null)

		capturedSupplemental?.toList() shouldContainExactly listOf<Byte>(3, 4)
		delegate.bytes.toByteArray().toList() shouldContainExactly listOf<Byte>(1, 2)
		delegate.metadata.single().flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA shouldBe 0
		delegate.metadata.single().size shouldBe 2
	}

	test("metadata offset retains already-buffered bytes for the next sample") {
		val delegate = RecordingTrackOutput()
		val transformed = mutableListOf<List<Byte>>()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			sourceBasePresentation = { DoviPresentation.HDR10 },
			transformer = DoviSampleTransformer { sample, _ ->
				transformed += sample.bytes.toList()
				result(sample.bytes, DoviPresentation.PROFILE_8_1)
			},
		)
		output.format(doviFormat(profile = 7))
		output.sampleData(ParsableByteArray(byteArrayOf(1, 2, 9)), 3, TrackOutput.SAMPLE_DATA_PART_MAIN)
		output.sampleMetadata(10, 0, 2, 1, null)
		output.sampleData(ParsableByteArray(byteArrayOf(10)), 1, TrackOutput.SAMPLE_DATA_PART_MAIN)
		output.sampleMetadata(20, 0, 2, 0, null)

		transformed shouldBe listOf(listOf<Byte>(1, 2), listOf<Byte>(9, 10))
	}

	test("transformation failure forwards neither advertised format nor sample") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			transformer = DoviSampleTransformer { _, _ -> error("native failure") },
		)
		output.format(doviFormat(profile = 7))
		output.sampleData(ParsableByteArray(byteArrayOf(1, 2)), 2, TrackOutput.SAMPLE_DATA_PART_MAIN)

		shouldThrow<IllegalStateException> { output.sampleMetadata(10, 0, 2, 0, null) }
		delegate.format shouldBe null
		delegate.bytes.size() shouldBe 0
		delegate.metadata.size shouldBe 0
	}

	test("encrypted sample maps to the stable transformation error") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			transformer = DoviSampleTransformer { sample, _ -> result(sample.bytes, DoviPresentation.PROFILE_8_1) },
		)
		output.format(doviFormat(profile = 7))
		output.sampleData(ParsableByteArray(byteArrayOf(1, 2)), 2, TrackOutput.SAMPLE_DATA_PART_MAIN)

		val error = shouldThrow<DoviSampleTransformationException> {
			output.sampleMetadata(10, C.BUFFER_FLAG_ENCRYPTED, 2, 0, null)
		}
		error.status shouldBe DoviStatus.REENCODE_REQUIRED
		RuntimeException(error).doviTransformationPlaybackErrorCode() shouldBe
			"DOVI_TRANSFORMATION_FAILED_REENCODE_REQUIRED"
		delegate.format shouldBe null
		delegate.bytes.size() shouldBe 0
	}

	test("a track without a transform request remains zero-copy pass-through") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { null },
			transformer = DoviSampleTransformer { _, _ -> error("must not transform") },
		)
		val format = doviFormat(profile = 7)
		output.format(format)
		output.sampleData(ParsableByteArray(byteArrayOf(1, 2)), 2, TrackOutput.SAMPLE_DATA_PART_MAIN)

		delegate.format shouldBe format
		delegate.bytes.toByteArray().toList() shouldContainExactly listOf<Byte>(1, 2)
	}

	test("partial access-unit bytes are bounded before metadata") {
		val delegate = RecordingTrackOutput()
		val output = DoviTrackOutput(
			delegate = delegate,
			request = { DoviTransformRequest(DoviTarget.PROFILE_8_1) },
			transformer = DoviSampleTransformer { sample, _ -> result(sample.bytes, DoviPresentation.PROFILE_8_1) },
		)
		output.format(doviFormat(profile = 7))
		val oversizedLength = 64 * 1024 * 1024 + 1

		shouldThrow<DoviSampleTransformationException> {
			output.sampleData(ParsableByteArray(ByteArray(1)), oversizedLength, TrackOutput.SAMPLE_DATA_PART_MAIN)
		}.status shouldBe DoviStatus.MALFORMED_SAMPLE
		delegate.bytes.size() shouldBe 0
	}
})

private fun result(
	bytes: ByteArray,
	output: DoviPresentation,
	repairs: Set<DoviRepair> = emptySet(),
	input: DoviPresentation = DoviPresentation.UNKNOWN,
) = DoviTransformResult(bytes, output, repairs, input)

private fun DoviTrackOutput.emit(bytes: ByteArray, timeUs: Long = 0) {
	sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
	sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
}

private fun doviFormat(profile: Int, prefix: String = "dvhe") = Format.Builder()
	.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
	.setCodecs("$prefix.${profile.toString().padStart(2, '0')}.06")
	.build()

internal class RecordingTrackOutput : TrackOutput {
	data class Metadata(val timeUs: Long, val flags: Int, val size: Int, val offset: Int)

	var format: Format? = null
	val formats = mutableListOf<Format>()
	val bytes = ByteArrayOutputStream()
	val metadata = mutableListOf<Metadata>()

	override fun format(format: Format) {
		this.format = format
		formats += format
	}

	override fun sampleData(
		input: androidx.media3.common.DataReader,
		length: Int,
		allowEndOfInput: Boolean,
		sampleDataPart: Int,
	): Int {
		val data = ByteArray(length)
		val read = input.read(data, 0, length)
		if (read > 0) bytes.write(data, 0, read)
		return read
	}

	override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
		bytes.write(data.data, data.position, length)
		data.skipBytes(length)
	}

	override fun sampleMetadata(
		timeUs: Long,
		flags: Int,
		size: Int,
		offset: Int,
		cryptoData: TrackOutput.CryptoData?,
	) {
		metadata += Metadata(timeUs, flags, size, offset)
	}
}

private val H265_SPS_FIXTURE = hexBytes(
	"420101222000000300B00000030000030099A001E020021C4D94BBB4A332AAC05A84489048A0000007D20000BB80E4687C9C00012E1F800021FD3000025C3F000043FA62",
)

private fun hexBytes(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
