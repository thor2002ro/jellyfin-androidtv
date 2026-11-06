package org.jellyfin.androidtv.ui.player.video

private const val PERCENT_SCALE = 100.0
private const val MILLIS_PER_SECOND = 1_000.0

internal fun MemoryUsageMetric.isAtLowMemoryThreshold(): Boolean {
	val total = totalBytes
	val used = usedBytes
	val threshold = lowMemoryThresholdBytes
	return if (total != null && total > 0L && used != null && used in 0L..total && threshold != null && threshold >= 0L) {
		total - used <= threshold
	} else lowMemory == true
}

internal data class IdleCpuSnapshot(val uptimeMs: Long, val idleMicros: List<Long?>)

internal fun idleCpuCorePercents(previous: IdleCpuSnapshot?, current: IdleCpuSnapshot): List<Float?> {
	val elapsedMicros = previous?.let { (current.uptimeMs - it.uptimeMs) * 1_000L } ?: 0L
	return current.idleMicros.mapIndexed { core, idle ->
		if (idle == null) return@mapIndexed 0f
		val before = previous?.idleMicros?.getOrNull(core)
		val delta = before?.let { idle - it }
		if (delta == null || delta < 0L || elapsedMicros <= 0L) null
		else (PERCENT_SCALE * (1.0 - delta.toDouble() / elapsedMicros)).coerceIn(0.0, PERCENT_SCALE).toFloat()
	}
}

internal data class CpuTimeSnapshot(val idle: Long, val total: Long)

internal data class SystemCpuSnapshot(val total: CpuTimeSnapshot, val cores: Map<Int, CpuTimeSnapshot>)

internal fun parseSystemCpu(text: String): SystemCpuSnapshot? {
	val counters = text.lineSequence().filter { it.startsWith("cpu") }.mapNotNull { line ->
		val fields = line.trim().split(Regex("\\s+"))
		val label = fields.first()
		if (label != "cpu" && label.removePrefix("cpu").toIntOrNull() == null) return@mapNotNull null
		// Guest time is already included in user/nice; only the first eight counters contribute to total.
		val values = fields.drop(1).take(8).map { it.toLongOrNull()?.takeIf { value -> value >= 0L } }
		if (values.size < 4 || values.any { it == null }) return@mapNotNull null
		val total = values.sumOf { it!! }
		if (total <= 0L) return@mapNotNull null
		label to CpuTimeSnapshot(values[3]!! + (values.getOrNull(4) ?: 0L), total)
	}.toMap()
	val total = counters["cpu"] ?: return null
	return SystemCpuSnapshot(total, counters.filterKeys { it != "cpu" }.mapKeys { it.key.removePrefix("cpu").toInt() })
}

internal fun cpuTimePercent(previous: CpuTimeSnapshot, current: CpuTimeSnapshot): Float? {
	val totalDelta = current.total - previous.total
	val idleDelta = current.idle - previous.idle
	if (totalDelta <= 0L || idleDelta < 0L) return null
	return (PERCENT_SCALE * (1.0 - idleDelta.toDouble() / totalDelta)).coerceIn(0.0, PERCENT_SCALE).toFloat()
}

internal class JavaHeapUsageTracker {
	private var count = 0L
	private var average = 0.0
	private var peak = 0L

	fun sample(usedBytes: Long, limitBytes: Long): JavaHeapUsageMetric {
		val sample = javaHeapUsage(usedBytes, limitBytes, peak)
		peak = sample.peakBytes ?: peak
		average += (sample.usedBytes!!.toDouble() - average) / ++count
		return sample.copy(averageBytes = average.toLong())
	}
}

internal fun idleCpuPercent(previous: IdleCpuSnapshot?, current: IdleCpuSnapshot): Float? {
	if (previous == null || previous.idleMicros.size != current.idleMicros.size || current.idleMicros.isEmpty()) return null
	val cores = idleCpuCorePercents(previous, current)
	if (cores.any { it == null } || current.idleMicros.all { it == null }) return null
	return cores.sumOf { it!!.toDouble() }.div(cores.size).toFloat()
}

internal data class ByteCounterSnapshot(val source: String, val readBytes: Long, val writeBytes: Long, val elapsedMs: Long)

internal data class ByteUsageMetric(
	val source: String? = null,
	val readBytesPerSecond: Float? = null,
	val writeBytesPerSecond: Float? = null,
	val readBytes: Long? = null,
	val writeBytes: Long? = null,
	val peakBytesPerSecond: Float? = null,
)

internal class ByteUsageTracker {
	private var previous: ByteCounterSnapshot? = null
	private var source: String? = null
	private var readBytes = 0L
	private var writeBytes = 0L
	private var peakBytesPerSecond: Float? = null

	fun sample(current: ByteCounterSnapshot?): ByteUsageMetric {
		val before = previous
		previous = current
		if (current == null) return ByteUsageMetric(source = source, peakBytesPerSecond = peakBytesPerSecond)
		if (current.source != source) {
			source = current.source
			readBytes = 0L
			writeBytes = 0L
			peakBytesPerSecond = null
		}
		val elapsedMs = before?.let { current.elapsedMs - it.elapsedMs } ?: 0L
		val readDelta = before?.let { current.readBytes - it.readBytes } ?: -1L
		val writeDelta = before?.let { current.writeBytes - it.writeBytes } ?: -1L
		val valid = before?.source == source && elapsedMs > 0L && readDelta >= 0L && writeDelta >= 0L
		var readRate: Float? = null
		var writeRate: Float? = null
		if (valid) {
			readBytes += readDelta
			writeBytes += writeDelta
			readRate = (readDelta.toDouble() * MILLIS_PER_SECOND / elapsedMs).toFloat()
			writeRate = (writeDelta.toDouble() * MILLIS_PER_SECOND / elapsedMs).toFloat()
			peakBytesPerSecond = maxOf(peakBytesPerSecond ?: 0f, readRate + writeRate)
		}
		return ByteUsageMetric(
			source = source,
			readBytesPerSecond = readRate,
			writeBytesPerSecond = writeRate,
			readBytes = readBytes,
			writeBytes = writeBytes,
			peakBytesPerSecond = peakBytesPerSecond,
		)
	}
}

internal fun parseProcessIo(text: String, elapsedMs: Long): ByteCounterSnapshot? {
	val fields = text.lineSequence().mapNotNull { line ->
		val value = line.substringAfter(':', "").trim().toLongOrNull()?.takeIf { it >= 0L } ?: return@mapNotNull null
		line.substringBefore(':') to value
	}.toMap()
	val storageRead = fields["read_bytes"]
	val storageWrite = fields["write_bytes"]
	if (storageRead != null && storageWrite != null) {
		return ByteCounterSnapshot("App storage", storageRead, storageWrite, elapsedMs)
	}
	// Syscall counts include cached reads and non-storage descriptors; keep their label distinct.
	if ("read_bytes" in fields || "write_bytes" in fields) return null
	val read = fields["rchar"] ?: return null
	val write = fields["wchar"] ?: return null
	return ByteCounterSnapshot("App I/O", read, write, elapsedMs)
}
