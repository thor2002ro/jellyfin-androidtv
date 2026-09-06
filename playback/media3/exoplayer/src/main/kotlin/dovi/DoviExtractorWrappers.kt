package org.jellyfin.playback.exoplayer.dovi

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import io.github.thor2002ro.libdovi.DoviPresentation
import io.github.thor2002ro.libdovi.DoviStatus
import io.github.thor2002ro.libdovi.DoviTarget
import java.util.TreeMap

private data class Mp4TrackIdentity(val trackId: Int, val baseTrackId: Int)

private fun Format.mp4TrackIdentity(): Mp4TrackIdentity? {
	val formatMetadata = metadata ?: return null
	val entries = (0 until formatMetadata.length()).map { formatMetadata[it] }
	val relations = entries.filterIsInstance<MdtaMetadataEntry>().filter { it.key == DOVI_TRACK_DEPENDENCY_KEY }
	if (relations.isEmpty()) return null
	if (relations.size != 1) throw dependencyFailure("Duplicate Dolby Vision dependency metadata")
	val relation = relations.single()
	val value = relation.value
	if (relation.typeIndicator != MdtaMetadataEntry.TYPE_INDICATOR_RESERVED ||
		relation.localeIndicator != MdtaMetadataEntry.DEFAULT_LOCALE_INDICATOR ||
		value.size != DOVI_TRACK_DEPENDENCY_SIZE ||
		!value.copyOfRange(0, 3).contentEquals(DOVI_TRACK_DEPENDENCY_MAGIC) ||
		value[3] != DOVI_TRACK_DEPENDENCY_VERSION
	) {
		throw dependencyFailure("Malformed Dolby Vision dependency metadata")
	}
	val trackId = value.readInt(4)
	val baseTrackId = value.readInt(8)
	if (trackId <= 0 || (baseTrackId != C.INDEX_UNSET && (baseTrackId <= 0 || baseTrackId == trackId))) {
		throw dependencyFailure("Invalid Dolby Vision dependency track identifiers")
	}
	return Mp4TrackIdentity(trackId, baseTrackId)
}

private fun ByteArray.readInt(offset: Int): Int =
	((this[offset].toInt() and 0xff) shl 24) or
		((this[offset + 1].toInt() and 0xff) shl 16) or
		((this[offset + 2].toInt() and 0xff) shl 8) or
		(this[offset + 3].toInt() and 0xff)

private fun dependencyFailure(message: String) =
	DoviSampleTransformationException(DoviStatus.INCONSISTENT_RPU, message)

private const val DOVI_TRACK_DEPENDENCY_KEY = "com.jellyfin.androidtv.dovi.track-dependency"
private const val DOVI_TRACK_DEPENDENCY_SIZE = 12
private val DOVI_TRACK_DEPENDENCY_MAGIC = byteArrayOf('D'.code.toByte(), 'V'.code.toByte(), 'D'.code.toByte())
private const val DOVI_TRACK_DEPENDENCY_VERSION: Byte = 1

private class DoviDualTrackDispatcher(
	private val requestTarget: DoviTarget,
) : DoviSampleDispatcher {
	private data class Pending(
		var base: Pair<DoviTrackOutput, DoviEncodedSample>? = null,
		var dependent: DoviEncodedSample? = null,
		var byteCount: Long = 0,
	)

	private val pending = TreeMap<Long, Pending>()
	private lateinit var baseOutput: DoviTrackOutput
	private lateinit var dependentOutput: DoviTrackOutput
	private var pendingBytes = 0L

	fun configure(base: DoviTrackOutput, dependent: DoviTrackOutput) {
		if (requestTarget == DoviTarget.LOSSLESS_REWRITE) {
			base.pairingFailure("Layered lossless Dolby Vision rewrite is unsupported")
		}
		baseOutput = base
		dependentOutput = dependent
	}

	override fun onFormat(output: DoviTrackOutput, format: Format) {
		if (pending.isNotEmpty()) output.pairingFailure("Dolby Vision track format changed with pending layered samples")
	}

	override fun onSample(output: DoviTrackOutput, sample: DoviEncodedSample) {
		val slot = pending.getOrPut(sample.timeUs) { Pending() }
		when (output) {
			baseOutput -> {
				if (slot.base != null) output.pairingFailure("Duplicate Dolby Vision base sample at ${sample.timeUs}")
				slot.base = output to sample
			}
			dependentOutput -> {
				if (slot.dependent != null) output.pairingFailure("Duplicate Dolby Vision dependent sample at ${sample.timeUs}")
				slot.dependent = sample
			}
			else -> output.pairingFailure("Dolby Vision sample came from an unselected track")
		}
		val retained = sample.bytesSize + (sample.supplementalRpu?.size ?: 0)
		slot.byteCount += retained
		pendingBytes += retained
		if (pending.size > MAX_PENDING_TIMESTAMPS || pendingBytes > MAX_PENDING_BYTES) {
			output.pairingFailure("Dolby Vision layered sample pairing exceeded its bounded buffer")
		}
		drain()
	}

	private fun drain() {
		while (pending.isNotEmpty()) {
			val first = pending.firstEntry() ?: return
			val base = first.value.base ?: return
			val dependent = first.value.dependent ?: return
			if (base.second.bytes.h265Nals().any { nal -> nal.type == DOVI_RPU_NAL_TYPE }) {
				base.first.pairingFailure("Layered Dolby Vision base access unit contains an in-band RPU at ${first.key}")
			}
			val dependentRpu = dependent.bytes.exactDependentRpu(base.first, first.key)
			val suppliedRpus = listOfNotNull(base.second.supplementalRpu, dependent.supplementalRpu)
			if (suppliedRpus.any { supplied -> !supplied.sameRpuAs(dependentRpu) }) {
				base.first.pairingFailure("Mismatched Dolby Vision supplemental RPU at ${first.key}")
			}
			base.first.emitTransformed(base.second, supplementalRpu = dependentRpu)
			pendingBytes -= first.value.byteCount
			pending.pollFirstEntry()
		}
	}

	fun finish() {
		if (pending.isNotEmpty()) {
			throw DoviSampleTransformationException(
				DoviStatus.INCONSISTENT_RPU,
				"Dolby Vision layered input ended with an incomplete timestamp ${pending.firstKey()}",
			)
		}
	}

	fun reset(abandonPending: Boolean) {
		if (!abandonPending) finish()
		pending.clear()
		pendingBytes = 0
	}

	private fun ByteArray.exactDependentRpu(output: DoviTrackOutput, timeUs: Long): ByteArray {
		val nals = h265Nals()
		if (nals.isEmpty()) output.pairingFailure("Malformed Dolby Vision dependent access unit at $timeUs")
		val rpus = nals.filter { nal -> nal.type == DOVI_RPU_NAL_TYPE }
		if (rpus.size != 1 || rpus.single().layer != 0) output.pairingFailure(
			"Dolby Vision dependent access unit at $timeUs requires exactly one layer-0 RPU",
		)
		return rpus.single().bytes
	}

	private fun ByteArray.sameRpuAs(other: ByteArray): Boolean =
		contentEquals(other) || h265Nals().singleOrNull { nal -> nal.type == DOVI_RPU_NAL_TYPE }?.bytes?.contentEquals(other) == true

	private companion object {
		const val DOVI_RPU_NAL_TYPE = 62
		const val MAX_PENDING_TIMESTAMPS = 8
		const val MAX_PENDING_BYTES = 16L * 1024L * 1024L
	}
}

private class DeferredVideoTrackOutput(
	val id: Int,
	val type: Int,
	private val formatChanged: (DeferredVideoTrackOutput, Format) -> Unit,
) : TrackOutput {
	private var prepared: TrackOutput? = null
	private var pendingDurationUs: Long? = null
	var format: Format? = null
		private set

	fun prepare(output: TrackOutput) {
		check(prepared == null) { "Dolby Vision track was prepared twice" }
		prepared = output
		pendingDurationUs?.let(output::durationUs)
		format?.let(output::format)
	}

	fun resetSampleState() = (prepared as? DoviTrackOutput)?.reset() ?: Unit

	override fun durationUs(durationUs: Long) {
		prepared?.durationUs(durationUs) ?: run { pendingDurationUs = durationUs }
	}

	override fun format(format: Format) {
		this.format = format
		formatChanged(this, format)
		prepared?.format(format)
	}

	override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
		(prepared ?: throw unprepared()).sampleData(input, length, allowEndOfInput, sampleDataPart)

	override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
		(prepared ?: throw unprepared()).sampleData(data, length, sampleDataPart)

	override fun sampleMetadata(
		timeUs: Long,
		flags: Int,
		size: Int,
		offset: Int,
		cryptoData: TrackOutput.CryptoData?,
	) = (prepared ?: throw unprepared()).sampleMetadata(timeUs, flags, size, offset, cryptoData)

	private fun unprepared() = DoviSampleTransformationException(
		DoviStatus.INCONSISTENT_RPU,
		"Dolby Vision sample arrived before its source track was selected",
	)
}

@UnstableApi
internal class DoviExtractorOutput(
	private val delegate: ExtractorOutput,
	private val context: () -> DoviTransformContext?,
	private val transformer: DoviSampleTransformer?,
) : ExtractorOutput {
	private val trackOutputs = mutableMapOf<Int, TrackOutput>()
	private val videoTracks = mutableListOf<DeferredVideoTrackOutput>()
	private var dispatcher: DoviDualTrackDispatcher? = null
	private var tracksEnded = false
	private var delegateEnded = false
	private val selectedIdentities = mutableMapOf<DeferredVideoTrackOutput, Mp4TrackIdentity>()

	override fun track(id: Int, type: Int): TrackOutput = trackOutputs.getOrPut(id) {
		if (type != C.TRACK_TYPE_VIDEO || context() == null) return@getOrPut delegate.track(id, type)
		DeferredVideoTrackOutput(id, type, ::onVideoFormat).also(videoTracks::add)
	}

	override fun endTracks() {
		if (tracksEnded) return
		tracksEnded = true
		tryPrepare()
	}

	private fun onVideoFormat(track: DeferredVideoTrackOutput, format: Format) {
		selectedIdentities[track]?.let { expected ->
			if (format.mp4TrackIdentity() != expected) throw pairingFailure("Selected Dolby Vision MP4 track identity changed")
		}
		if (tracksEnded && !delegateEnded) tryPrepare()
	}

	private fun tryPrepare() {
		if (!tracksEnded || delegateEnded) return
		val transformContext = context()
		if (transformContext == null || videoTracks.isEmpty()) return finishDelegateTracks()
		if (videoTracks.any { it.format == null }) return
		if (videoTracks.size == 1) {
			val only = videoTracks.single()
			if (!only.format.isTransformCandidate()) throw pairingFailure("The only video track is not HEVC Dolby Vision")
			only.prepare(newDoviOutput(delegate.track(only.id, only.type), null))
			return finishDelegateTracks()
		}
		if (!transformContext.pairEnhancementTrack || videoTracks.size != 2) {
			throw pairingFailure("Multiple video tracks have no authorized Dolby Vision dependency relation")
		}
		val identities = videoTracks.associateWith { track ->
			track.format?.mp4TrackIdentity() ?: throw pairingFailure("Layered Dolby Vision requires MP4 tref/vdep metadata")
		}
		val dependent = identities.entries.singleOrNull { it.value.baseTrackId != C.INDEX_UNSET }
			?: throw pairingFailure("Layered Dolby Vision requires exactly one dependent MP4 track")
		if (dependent.value.trackId == dependent.value.baseTrackId) {
			throw pairingFailure("Dolby Vision vdep cannot reference its own track")
		}
		val base = identities.entries.singleOrNull { it.value.trackId == dependent.value.baseTrackId }
			?: throw pairingFailure("Dolby Vision vdep does not reference the selected base track")
		if (base.key === dependent.key || base.value.baseTrackId != C.INDEX_UNSET ||
			identities.values.map(Mp4TrackIdentity::trackId).distinct().size != identities.size
		) {
			throw pairingFailure("Dolby Vision MP4 dependency identities are inconsistent")
		}
		if (!base.key.format.isTransformCandidate() || !dependent.key.format.isTransformCandidate()) {
			throw pairingFailure("Dolby Vision MP4 relation points to a non-HEVC track")
		}
		selectedIdentities[base.key] = base.value
		selectedIdentities[dependent.key] = dependent.value
		val pairedDispatcher = DoviDualTrackDispatcher(transformContext.request.target)
		val baseOutput = newDoviOutput(delegate.track(base.key.id, base.key.type), pairedDispatcher)
		val dependentOutput = newDoviOutput(DiscardingTrackOutput(), pairedDispatcher)
		pairedDispatcher.configure(baseOutput, dependentOutput)
		dispatcher = pairedDispatcher
		base.key.prepare(baseOutput)
		dependent.key.prepare(dependentOutput)
		finishDelegateTracks()
	}

	private fun Format?.isTransformCandidate(): Boolean = this != null &&
		(sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || sampleMimeType == MimeTypes.VIDEO_H265) &&
		codecs?.contains(Regex("(?i)(?:^|,)\\s*dav1\\.")) != true

	private fun finishDelegateTracks() {
		delegate.endTracks()
		delegateEnded = true
	}

	private fun newDoviOutput(output: TrackOutput, sampleDispatcher: DoviSampleDispatcher?) = DoviTrackOutput(
		delegate = output,
		request = { context()?.request },
		sourceBasePresentation = { context()?.sourceBasePresentation ?: DoviPresentation.UNKNOWN },
		dvLevel = { context()?.dvLevel },
		transformer = transformer,
		dispatcher = sampleDispatcher,
		onTransformObserved = { observation -> context()?.onTransformObserved?.invoke(observation) },
	)

	override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)

	fun finish() = dispatcher?.finish() ?: Unit

	fun reset(abandonPending: Boolean, clearTracks: Boolean) {
		dispatcher?.reset(abandonPending)
		videoTracks.forEach(DeferredVideoTrackOutput::resetSampleState)
		if (clearTracks) {
			trackOutputs.clear()
			videoTracks.clear()
			dispatcher = null
			tracksEnded = false
			delegateEnded = false
			selectedIdentities.clear()
		}
	}

	private fun pairingFailure(message: String) = DoviSampleTransformationException(DoviStatus.INCONSISTENT_RPU, message)
}

@UnstableApi
internal class DoviExtractor(
	private val delegate: Extractor,
	private val context: () -> DoviTransformContext?,
	private val transformer: DoviSampleTransformer? = null,
) : Extractor {
	private var output: DoviExtractorOutput? = null

	override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

	override fun init(output: ExtractorOutput) {
		this.output?.reset(abandonPending = false, clearTracks = true)
		DoviExtractorOutput(output, context, transformer).also {
			this.output = it
			delegate.init(it)
		}
	}

	override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
		delegate.read(input, seekPosition).also { result ->
			if (result == Extractor.RESULT_END_OF_INPUT) output?.finish()
		}

	override fun seek(position: Long, timeUs: Long) {
		output?.reset(abandonPending = true, clearTracks = false)
		delegate.seek(position, timeUs)
	}

	override fun release() {
		output?.reset(abandonPending = false, clearTracks = true)
		output = null
		delegate.release()
	}

	override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@UnstableApi
internal class DoviHlsMediaChunkExtractor(
	private val delegate: HlsMediaChunkExtractor,
	private val context: () -> DoviTransformContext?,
	private val transformer: DoviSampleTransformer? = null,
) : HlsMediaChunkExtractor {
	private var output: DoviExtractorOutput? = null

	override fun init(extractorOutput: ExtractorOutput) {
		output?.reset(abandonPending = false, clearTracks = true)
		DoviExtractorOutput(extractorOutput, context, transformer).also {
			output = it
			delegate.init(it)
		}
	}

	override fun read(extractorInput: ExtractorInput): Boolean = delegate.read(extractorInput).also { hasMore ->
		if (!hasMore) output?.finish()
	}
	override fun isPackedAudioExtractor(): Boolean = delegate.isPackedAudioExtractor
	override fun isReusable(): Boolean = delegate.isReusable
	override fun recreate(): HlsMediaChunkExtractor =
		DoviHlsMediaChunkExtractor(delegate.recreate(), context, transformer)

	override fun onTruncatedSegmentParsed() {
		output?.reset(abandonPending = true, clearTracks = false)
		delegate.onTruncatedSegmentParsed()
	}
}
