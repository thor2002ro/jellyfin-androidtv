package org.jellyfin.playback.exoplayer.dovi

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.TrackOutput
import io.github.thor2002ro.libdovi.DoviBridge
import io.github.thor2002ro.libdovi.DoviFraming
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviSample
import io.github.thor2002ro.libdovi.DoviStatus
import io.github.thor2002ro.libdovi.DoviTarget
import io.github.thor2002ro.libdovi.DoviTransformBuffer
import io.github.thor2002ro.libdovi.DoviTransformRequest
import io.github.thor2002ro.libdovi.DoviTransformResult
import io.github.thor2002ro.libdovi.DoviTransformObservation
import org.jellyfin.playback.dovi.DoviSourceBaseStrategy
import timber.log.Timber
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface DoviSampleTransformer {
	fun transform(sample: DoviSample, request: DoviTransformRequest): DoviTransformResult
}

internal class DoviSampleTransformationException(
	val status: DoviStatus,
	message: String,
) : RuntimeException(message)

internal data class DoviEncodedSample(
	val bytes: ByteArray,
	val bytesSize: Int,
	val supplementalRpu: ByteArray?,
	val timeUs: Long,
	val flags: Int,
)

internal data class H265Nal(
	val bytes: ByteArray,
	val type: Int,
	val layer: Int,
)

private enum class DoviSourceBaseState {
	VALIDATING,
	FAST,
	LIBDOVI,
}

internal class DoviSourceBasePlaybackState {
	private val fastPathRejected = AtomicBoolean(false)

	val isFastPathRejected: Boolean
		get() = fastPathRejected.get()

	fun rejectFastPath() {
		fastPathRejected.set(true)
	}
}

private class FastSourceBaseFilterException(message: String) : RuntimeException(message)

internal interface DoviSampleDispatcher {
	fun onFormat(output: DoviTrackOutput, format: Format)
	fun onSample(output: DoviTrackOutput, sample: DoviEncodedSample)
}

internal fun transformDoviSample(
	sample: DoviSample,
	request: DoviTransformRequest,
): DoviTransformResult = DoviBridge.transform(sample, request)

@UnstableApi
internal class DoviTrackOutput(
	private val delegate: TrackOutput,
	private val request: () -> DoviTransformRequest?,
	private val sourceBasePresentation: () -> DoviPresentation = { DoviPresentation.UNKNOWN },
	private val dvLevel: () -> Int? = { null },
	private val sourceBaseStrategy: () -> DoviSourceBaseStrategy = { DoviSourceBaseStrategy.LIBDOVI },
	private val sourceBasePlaybackState: DoviSourceBasePlaybackState = DoviSourceBasePlaybackState(),
	private val transformer: DoviSampleTransformer? = null,
	private val dispatcher: DoviSampleDispatcher? = null,
	private val onTransformObserved: (DoviTransformObservation) -> Unit = {},
) : TrackOutput {
	private data class PartRange(
		val start: Int,
		val end: Int,
		val part: Int,
	)

	private class ReusableByteBuffer {
		var bytes = ByteArray(0)
			private set
		var size = 0
			private set

		fun prepareAppend(length: Int): Int {
			val start = size
			ensureCapacity(size + length)
			return start
		}

		fun commitAppend(length: Int) {
			size += length
		}

		fun append(source: ByteArray, offset: Int, length: Int) {
			if (length <= 0) return
			val start = prepareAppend(length)
			System.arraycopy(source, offset, bytes, start, length)
			commitAppend(length)
		}

		fun reset() {
			size = 0
		}

		fun retainFrom(position: Int) {
			val retained = size - position
			if (retained > 0) System.arraycopy(bytes, position, bytes, 0, retained)
			size = retained
		}

		fun extractRpu(mainSize: Int): ByteArray? {
			if (size == 0) return null
			if (size < 4) return bytes.copyOf(size)
			val declaredMainSize =
				((bytes[0].toInt() and 0xff) shl 24) or
					((bytes[1].toInt() and 0xff) shl 16) or
					((bytes[2].toInt() and 0xff) shl 8) or
					(bytes[3].toInt() and 0xff)
			val start = if (declaredMainSize == mainSize) 4 else 0
			return bytes.copyOfRange(start, size).takeIf(ByteArray::isNotEmpty)
		}

		private fun ensureCapacity(required: Int) {
			if (required <= bytes.size) return
			var capacity = bytes.size.coerceAtLeast(1)
			while (capacity < required) {
				capacity = minOf(MAX_PARTIAL_SAMPLE_BYTES.toInt(), maxOf(required, capacity * 2))
			}
			bytes = bytes.copyOf(capacity)
		}
	}

	private val pendingBytes = ReusableByteBuffer()
	private val mainBytes = ReusableByteBuffer()
	private val supplementalBytes = ReusableByteBuffer()
	private val directOutputBytes = ReusableByteBuffer()
	private val pendingParts = mutableListOf<PartRange>()
	private val transformBuffer = DoviTransformBuffer()
	private var sourceFormat: Format? = null
	private var activeRequest: DoviTransformRequest? = null
	private var signaledFormat: Format? = null
	private var validatedOutput: DoviPresentation? = null
	private var transformObserved = false
	private var sourceBaseState = DoviSourceBaseState.LIBDOVI

	override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

	override fun format(format: Format) {
		resetSampleState()
		signaledFormat = null
		validatedOutput = null
		sourceFormat = format
		activeRequest = request().takeIf { format.isHevcDolbyVision() }
		sourceBaseState = if (
			activeRequest?.target == DoviTarget.SOURCE_BASE_PRESENTATION &&
			sourceBaseStrategy() == DoviSourceBaseStrategy.FAST_HDR_BASE_FALLBACK &&
			!sourceBasePlaybackState.isFastPathRejected
		) {
			DoviSourceBaseState.VALIDATING
		} else {
			DoviSourceBaseState.LIBDOVI
		}
		if (activeRequest == null) {
			delegate.format(format)
		} else {
			dispatcher?.onFormat(this, format)
		}
	}

	override fun sampleData(
		input: DataReader,
		length: Int,
		allowEndOfInput: Boolean,
		sampleDataPart: Int,
	): Int {
		if (activeRequest == null) {
			return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
		}

		ensureCanAppend(length)
		val start = pendingBytes.prepareAppend(length)
		val read = input.read(pendingBytes.bytes, start, length)
		if (read == C.RESULT_END_OF_INPUT) {
			if (allowEndOfInput) return C.RESULT_END_OF_INPUT
			throw EOFException()
		}
		if (read > 0) {
			pendingBytes.commitAppend(read)
			pendingParts += PartRange(start, start + read, sampleDataPart)
		}
		return read
	}

	override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
		if (activeRequest == null) {
			delegate.sampleData(data, length, sampleDataPart)
			return
		}

		ensureCanAppend(length)
		val start = pendingBytes.prepareAppend(length)
		System.arraycopy(data.data, data.position, pendingBytes.bytes, start, length)
		data.position += length
		if (length > 0) {
			pendingBytes.commitAppend(length)
			pendingParts += PartRange(start, start + length, sampleDataPart)
		}
	}

	override fun sampleMetadata(
		timeUs: Long,
		flags: Int,
		size: Int,
		offset: Int,
		cryptoData: TrackOutput.CryptoData?,
	) {
		val transformRequest = activeRequest
		if (transformRequest == null) {
			delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
			return
		}
		if (size < 0 || offset < 0) {
			throw failure(DoviStatus.MALFORMED_SAMPLE, "Negative Dolby Vision sample size or offset")
		}
		if (cryptoData != null || flags and C.BUFFER_FLAG_ENCRYPTED != 0) {
			throw failure(DoviStatus.REENCODE_REQUIRED, "Encrypted Dolby Vision samples cannot be transformed")
		}

		val sampleEnd = pendingBytes.size - offset
		val sampleStart = sampleEnd - size
		if (sampleStart != 0 || sampleEnd !in 0..pendingBytes.size) {
			throw failure(
				DoviStatus.MALFORMED_SAMPLE,
				"Inconsistent Dolby Vision sample metadata: buffered=${pendingBytes.size}, size=$size, offset=$offset",
			)
		}

		mainBytes.reset()
		supplementalBytes.reset()
		for (range in pendingParts) {
			val intersectionStart = maxOf(range.start, sampleStart)
			val intersectionEnd = minOf(range.end, sampleEnd)
			if (intersectionStart >= intersectionEnd) continue
			val target = when (range.part) {
				TrackOutput.SAMPLE_DATA_PART_MAIN -> mainBytes
				TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL -> supplementalBytes
				else -> throw failure(
					DoviStatus.INVALID_ARGUMENT,
					"Unsupported Dolby Vision sample data part: ${range.part}",
				)
			}
			target.append(pendingBytes.bytes, intersectionStart, intersectionEnd - intersectionStart)
		}

		if (mainBytes.size == 0) throw failure(DoviStatus.MALFORMED_SAMPLE, "Dolby Vision sample has no main data")
		val encoded = DoviEncodedSample(
			bytes = mainBytes.bytes,
			bytesSize = mainBytes.size,
			supplementalRpu = supplementalBytes.extractRpu(mainBytes.size),
			timeUs = timeUs,
			flags = flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA.inv(),
		)
		if (dispatcher != null) {
			dispatcher.onSample(
				this,
				encoded.copy(bytes = encoded.bytes.copyOf(encoded.bytesSize)),
			)
			retainFrom(sampleEnd)
			return
		}
		emitTransformed(encoded)
		retainFrom(sampleEnd)
	}

	internal fun emitTransformed(
		sample: DoviEncodedSample,
		supplementalRpu: ByteArray? = sample.supplementalRpu,
	) {
		if (sourceBaseState == DoviSourceBaseState.FAST) {
			try {
				emitFastSourceBase(sample)
				return
			} catch (exception: FastSourceBaseFilterException) {
				directOutputBytes.reset()
				sourceBasePlaybackState.rejectFastPath()
				sourceBaseState = DoviSourceBaseState.LIBDOVI
				Timber.w(exception, "Fast HDR base fallback rejected a sample; using libdovi for this track")
			}
		}
		emitWithLibdovi(sample, supplementalRpu)
	}

	private fun emitWithLibdovi(sample: DoviEncodedSample, supplementalRpu: ByteArray?) {
		val transformRequest = requireNotNull(activeRequest) { "Dolby Vision request is not active" }
		val doviSample = DoviSample(
			bytes = sample.bytes,
			bytesSize = sample.bytesSize,
			framing = DoviFraming.ANNEX_B,
			sourceBasePresentation = sourceBasePresentation(),
			supplementalRpu = supplementalRpu,
		)
		val injectedResult = transformer?.transform(doviSample, transformRequest)
		val bufferedResult = if (injectedResult == null) {
			DoviBridge.transform(doviSample, transformRequest, transformBuffer)
		} else {
			null
		}
		val resultBytes = injectedResult?.bytes ?: requireNotNull(bufferedResult).bytes
		val resultSize = injectedResult?.bytes?.size ?: requireNotNull(bufferedResult).bytesSize
		val resultInput = injectedResult?.input ?: requireNotNull(bufferedResult).input
		val resultOutput = injectedResult?.output ?: requireNotNull(bufferedResult).output
		val source = requireNotNull(sourceFormat) {
			"Dolby Vision sample arrived before its format"
		}
		val expected = transformRequest.expectedOutput(resultInput, sourceBasePresentation())
		if (resultOutput != expected) {
			throw failure(
				DoviStatus.INTERNAL_ERROR,
				"Native transformation returned $resultOutput, expected $expected",
			)
		}
		val priorOutput = validatedOutput
		if (priorOutput != null && priorOutput != resultOutput) {
			throw failure(DoviStatus.INTERNAL_ERROR, "Native transformation output changed within the track")
		}
		validatedOutput = resultOutput
		if (sourceBaseState == DoviSourceBaseState.VALIDATING) {
			sourceBaseState = if (
				resultInput == DoviPresentation.PROFILE_8_1 &&
				resultOutput == sourceBasePresentation() &&
				resultOutput in setOf(DoviPresentation.HDR10, DoviPresentation.HDR10_PLUS)
			) {
				Timber.i("Fast HDR base fallback enabled after libdovi validation output=%s", resultOutput)
				DoviSourceBaseState.FAST
			} else {
				DoviSourceBaseState.LIBDOVI
			}
		}
		if (!transformObserved) {
			onTransformObserved(DoviTransformObservation(resultInput, resultOutput))
			transformObserved = true
		}
		val outputFormat = source.forDoviPresentation(resultOutput, dvLevel())
		if (signaledFormat != outputFormat) {
			delegate.format(outputFormat)
			signaledFormat = outputFormat
		}

		delegate.sampleData(
			ParsableByteArray(resultBytes, resultSize),
			resultSize,
			TrackOutput.SAMPLE_DATA_PART_MAIN,
		)
		delegate.sampleMetadata(
			sample.timeUs,
			sample.flags,
			resultSize,
			0,
			null,
		)
	}

	private fun emitFastSourceBase(sample: DoviEncodedSample) {
		val outputPresentation = requireNotNull(validatedOutput) {
			"Direct Dolby Vision source-base removal requires validated output"
		}
		val resultSize = filterSourceBaseAnnexB(sample.bytes, sample.bytesSize, directOutputBytes)
		val source = requireNotNull(sourceFormat) {
			"Dolby Vision sample arrived before its format"
		}
		val outputFormat = source.forDoviPresentation(outputPresentation, dvLevel())
		if (signaledFormat != outputFormat) {
			delegate.format(outputFormat)
			signaledFormat = outputFormat
		}
		delegate.sampleData(
			ParsableByteArray(directOutputBytes.bytes, resultSize),
			resultSize,
			TrackOutput.SAMPLE_DATA_PART_MAIN,
		)
		delegate.sampleMetadata(sample.timeUs, sample.flags, resultSize, 0, null)
	}

	private fun filterSourceBaseAnnexB(
		input: ByteArray,
		inputSize: Int,
		output: ReusableByteBuffer,
	): Int {
		fun startCodeLength(index: Int): Int = when {
			index + 3 < inputSize && input[index] == 0.toByte() && input[index + 1] == 0.toByte() &&
				input[index + 2] == 0.toByte() && input[index + 3] == 1.toByte() -> 4
			index + 2 < inputSize && input[index] == 0.toByte() && input[index + 1] == 0.toByte() &&
				input[index + 2] == 1.toByte() -> 3
			else -> 0
		}

		output.reset()
		var position = 0
		var nalCount = 0
		while (position < inputSize) {
			val prefixSize = startCodeLength(position)
			if (prefixSize == 0) throw FastSourceBaseFilterException("Invalid Annex-B start code")
			val nalStart = position + prefixSize
			var nextPosition = nalStart
			while (nextPosition < inputSize && startCodeLength(nextPosition) == 0) nextPosition++
			if (nextPosition - nalStart < 2) {
				throw FastSourceBaseFilterException("Dolby Vision Annex-B NAL is truncated")
			}
			val first = input[nalStart].toInt() and 0xff
			val second = input[nalStart + 1].toInt() and 0xff
			val type = (first ushr 1) and 0x3f
			val layer = ((first and 1) shl 5) or ((second ushr 3) and 0x1f)
			if (type != 62 && type != 63 && layer == 0) {
				output.append(input, position, nextPosition - position)
			}
			nalCount++
			position = nextPosition
		}
		if (nalCount == 0) throw FastSourceBaseFilterException("Dolby Vision Annex-B sample is empty")
		if (output.size == 0) throw FastSourceBaseFilterException("Dolby Vision Annex-B sample has no HDR base data")
		return output.size
	}

	/** Drops partial access-unit data while retaining the track format and exact request. */
	fun reset() {
		resetSampleState()
		signaledFormat = null
	}

	internal fun pairingFailure(message: String): Nothing =
		throw failure(DoviStatus.INCONSISTENT_RPU, message)

	private fun resetSampleState() {
		pendingBytes.reset()
		pendingParts.clear()
	}

	private fun ensureCanAppend(length: Int) {
		if (length < 0 || pendingBytes.size.toLong() + length > MAX_PARTIAL_SAMPLE_BYTES) {
			resetSampleState()
			throw failure(DoviStatus.MALFORMED_SAMPLE, "Dolby Vision partial sample exceeded its bounded buffer")
		}
	}

	private fun retainFrom(position: Int) {
		val retainedParts = pendingParts.mapNotNull { range ->
			if (range.end <= position) null
			else PartRange(
				start = maxOf(range.start, position) - position,
				end = range.end - position,
				part = range.part,
			)
		}
		pendingBytes.retainFrom(position)
		pendingParts.clear()
		pendingParts += retainedParts
	}

	private fun failure(status: DoviStatus, message: String) =
		DoviSampleTransformationException(status, message)
}

private fun Format.isHevcDolbyVision(): Boolean {
	if (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION && sampleMimeType != MimeTypes.VIDEO_H265) return false
	return codecs?.contains(AV1_DOVI_CODEC) != true
}

private fun Format.forDoviPresentation(presentation: DoviPresentation, dvLevel: Int?): Format = when (presentation) {
	DoviPresentation.PROFILE_5 -> asDoviProfile(5, dvLevel)
	DoviPresentation.PROFILE_7_MEL,
	DoviPresentation.PROFILE_7_FEL,
	-> asDoviProfile(7, dvLevel)
	DoviPresentation.PROFILE_8_1,
	DoviPresentation.PROFILE_8_4,
	-> asDoviProfile(8, dvLevel)
	DoviPresentation.HDR10,
	DoviPresentation.HDR10_PLUS,
	DoviPresentation.HLG,
	-> asSourceBase(presentation)
	DoviPresentation.UNKNOWN -> error("Unknown Dolby Vision output presentation")
}

private fun Format.asDoviProfile(profile: Int, evidenceLevel: Int?): Format {
	val match = codecs?.let(DOVI_CODEC::find)
	val level = match?.groupValues?.get(3)?.toIntOrNull() ?: evidenceLevel
	if (level == null || level !in 0..63) throw DoviSampleTransformationException(
		DoviStatus.INTERNAL_ERROR,
		"Dolby Vision output requires an exact level",
	)
	val prefix = match?.groupValues?.get(1)?.lowercase() ?: "dvhe"
	val rewrittenCodecs = "$prefix.${profile.toString().padStart(2, '0')}.${level.toString().padStart(2, '0')}"
	return buildUpon()
		.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
		.setCodecs(rewrittenCodecs)
		.build()
}

private fun Format.asSourceBase(presentation: DoviPresentation): Format {
	val transfer = when (presentation) {
		DoviPresentation.HDR10, DoviPresentation.HDR10_PLUS -> C.COLOR_TRANSFER_ST2084
		DoviPresentation.HLG -> C.COLOR_TRANSFER_HLG
		else -> error("Not a source-base presentation: $presentation")
	}
	val baseColor = colorInfo
	val color = (baseColor?.buildUpon() ?: ColorInfo.Builder())
		.setColorTransfer(transfer)
		.apply {
			if (presentation == DoviPresentation.HLG) setHdrStaticInfo(null)
		}
		.build()
	val hevcCodec = codecs
		?.split(',')
		?.map(String::trim)
		?.firstOrNull { codec -> HEVC_CODEC.matches(codec) }
		?: deriveHevcCodec()

	return buildUpon()
		.setSampleMimeType(MimeTypes.VIDEO_H265)
		.setCodecs(hevcCodec)
		.setColorInfo(color)
		.setInitializationData(initializationData.filterNot { data -> data.isDoviConfigurationRecord() })
		.build()
}

private fun Format.deriveHevcCodec(): String {
	val sps = initializationData.asSequence()
		.flatMap { data -> data.h265Nals().asSequence() }
		.firstOrNull { nal -> nal.type == NalUnitUtil.H265_NAL_UNIT_TYPE_SPS && nal.layer == 0 }
		?: throw DoviSampleTransformationException(
			DoviStatus.INTERNAL_ERROR,
			"Source-base output requires layer-0 HEVC SPS initialization data",
		)
	return try {
		val parsed = NalUnitUtil.parseH265SpsNalUnit(sps.bytes, 0, sps.bytes.size, null)
		val ptl = parsed.profileTierLevel ?: throw IllegalArgumentException("Missing profile-tier-level")
		CodecSpecificDataUtil.buildHevcCodecString(
			ptl.generalProfileSpace,
			ptl.generalTierFlag,
			ptl.generalProfileIdc,
			ptl.generalProfileCompatibilityFlags,
			ptl.constraintBytes,
			ptl.generalLevelIdc,
		)
	} catch (exception: RuntimeException) {
		throw DoviSampleTransformationException(
			DoviStatus.MALFORMED_SAMPLE,
			"Invalid HEVC SPS initialization data: ${exception.message}",
		)
	}
}

private fun ByteArray.isDoviConfigurationRecord(): Boolean {
	if (size !in 5..24 || this[0].toInt() != 1 || this[1].toInt() != 0) return false
	val profile = (this[2].toInt() and 0xff) ushr 1
	return profile in 1..10
}

private val DOVI_CODEC = Regex("(?i)(?:^|,)\\s*(dvhe|dvh1)\\.(\\d{2})\\.(\\d{2})(?:\\.[A-Za-z0-9]+)*\\s*(?=,|$)")
private val HEVC_CODEC = Regex("(?i)(?:hvc1|hev1)\\.[A-Za-z0-9]+(?:\\.[A-Za-z0-9]+)+")
private val AV1_DOVI_CODEC = Regex("(?i)(?:^|,)\\s*dav1\\.")
private const val MAX_PARTIAL_SAMPLE_BYTES = 64L * 1024L * 1024L

private fun DoviTransformRequest.expectedOutput(
	input: DoviPresentation,
	sourceBase: DoviPresentation,
): DoviPresentation = when (target) {
	DoviTarget.LOSSLESS_REWRITE -> input
	DoviTarget.MEL -> DoviPresentation.PROFILE_7_MEL
	DoviTarget.PROFILE_8_1,
	DoviTarget.PROFILE_8_1_PRESERVE_MAPPING,
	-> DoviPresentation.PROFILE_8_1
	DoviTarget.PROFILE_8_4 -> DoviPresentation.PROFILE_8_4
	DoviTarget.SOURCE_BASE_PRESENTATION -> sourceBase
}

internal fun ByteArray.h265Nals(): List<H265Nal> {
	fun startCodeLength(index: Int): Int = when {
		index + 3 < size && this[index] == 0.toByte() && this[index + 1] == 0.toByte() &&
			this[index + 2] == 0.toByte() && this[index + 3] == 1.toByte() -> 4
		index + 2 < size && this[index] == 0.toByte() && this[index + 1] == 0.toByte() &&
			this[index + 2] == 1.toByte() -> 3
		else -> 0
	}
	val result = mutableListOf<H265Nal>()
	var start = indices.firstOrNull { startCodeLength(it) > 0 } ?: return emptyList()
	while (start < size) {
		val header = start + startCodeLength(start)
		var next = header
		while (next < size && startCodeLength(next) == 0) next++
		if (next - header >= 2) {
			val first = this[header].toInt() and 0xff
			val second = this[header + 1].toInt() and 0xff
			result += H265Nal(
				bytes = copyOfRange(header, next),
				type = (first ushr 1) and 0x3f,
				layer = ((first and 1) shl 5) or ((second ushr 3) and 0x1f),
			)
		}
		start = next
	}
	return result
}
