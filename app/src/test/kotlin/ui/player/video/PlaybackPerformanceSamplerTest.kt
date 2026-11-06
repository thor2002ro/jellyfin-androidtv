package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

class PlaybackPerformanceSamplerTest : FunSpec({
	test("core panel is shown when at least one core has utilization") {
		UsageMetric("CPU", null).hasCpuCoreUsage() shouldBe false
		UsageMetric("CPU", null, corePercents = mapOf(0 to null, 1 to null)).hasCpuCoreUsage() shouldBe false
		UsageMetric("CPU", null, corePercents = mapOf(0 to Float.NaN)).hasCpuCoreUsage() shouldBe false
		UsageMetric("CPU", null, corePercents = mapOf(0 to null, 1 to 0f)).hasCpuCoreUsage() shouldBe true
		UsageMetric("CPU", null, corePercents = mapOf(0 to 50f)).hasCpuCoreUsage() shouldBe true
	}

	test("network totals accumulate without overlay observers and reset with the playback session") {
		coroutineScope {
			val reads = AtomicInteger()
			val read = {
				val index = reads.getAndIncrement().toLong()
				ByteCounterSnapshot("App", index * 100, index * 50, index * 1_000)
			}
			val context = mockk<Context>()
			val first = PlaybackNetworkUsageService(context, read)
			val job = launch { first.onInitialize() }
			try {
				withTimeout(5_000) { while (first.samples.value.dataUsage.readBytes != 200L) delay(10) }
				first.samples.value.dataUsage.writeBytes shouldBe 100L
			} finally { job.cancelAndJoin() }
			first.samples.value.dataUsage.readBytes shouldBe null
			val countAfterStop = reads.get()
			delay(50)
			reads.get() shouldBe countAfterStop
			val next = PlaybackNetworkUsageService(context, read)
			val nextJob = launch { next.onInitialize() }
			try {
				withTimeout(5_000) { while (next.samples.value.dataUsage.readBytes != 100L) delay(10) }
				next.samples.value.dataUsage.writeBytes shouldBe 50L
			} finally { nextJob.cancelAndJoin() }
		}
	}

	test("RAM warning starts at the free memory threshold and clears above it") {
		val memory = MemoryUsageMetric(usedBytes = 800, totalBytes = 1_000, lowMemoryThresholdBytes = 200)
		memory.isAtLowMemoryThreshold() shouldBe true
		memory.copy(usedBytes = 801).isAtLowMemoryThreshold() shouldBe true
		memory.copy(usedBytes = 799).isAtLowMemoryThreshold() shouldBe false
		MemoryUsageMetric().isAtLowMemoryThreshold() shouldBe false
		MemoryUsageMetric(lowMemory = true).isAtLowMemoryThreshold() shouldBe true
	}

	test("CPU core readings preserve offline and unavailable cores") {
		val before = IdleCpuSnapshot(1_000, listOf(100L, null, 100L))
		idleCpuCorePercents(before, IdleCpuSnapshot(2_000, listOf(250_100L, null, 750_100L))) shouldBe listOf(75f, 0f, 25f)
		idleCpuCorePercents(before, IdleCpuSnapshot(2_000, listOf(250_100L, 100L, 50L))) shouldBe listOf(75f, null, null)
	}

	test("proc CPU parsing retains core IDs and excludes guest counters from totals") {
		val before = parseSystemCpu("cpu 100 0 0 100 0 0 0 0 50 0\ncpu0 50 0 0 50\ncpu2 50 0 0 50")!!
		val after = parseSystemCpu("cpu 200 0 0 200 0 0 0 0 100 0\ncpu0 125 0 0 75\ncpu2 75 0 0 125")!!
		cpuTimePercent(before.total, after.total) shouldBe 50f
		cpuTimePercent(before.cores[0]!!, after.cores[0]!!) shouldBe 75f
		cpuTimePercent(before.cores[2]!!, after.cores[2]!!) shouldBe 25f
		after.cores.keys shouldBe setOf(0, 2)
		parseSystemCpu("cpu 100 broken 0 100") shouldBe null
	}

	test("heap average covers all samples and keeps its peak after reclamation") {
		val tracker = JavaHeapUsageTracker()
		tracker.sample(100, 512).averageBytes shouldBe 100L
		tracker.sample(300, 512).averageBytes shouldBe 200L
		val next = tracker.sample(200, 512)
		next.averageBytes shouldBe 200L
		next.peakBytes shouldBe 300L
	}

	test("IO peak survives quieter samples and resets with its counter source") {
		val tracker = ByteUsageTracker()
		tracker.sample(ByteCounterSnapshot("App storage", 0, 0, 1_000))
		tracker.sample(ByteCounterSnapshot("App storage", 1_000, 2_000, 2_000)).peakBytesPerSecond shouldBe 3_000f
		tracker.sample(ByteCounterSnapshot("App storage", 1_100, 2_100, 3_000)).peakBytesPerSecond shouldBe 3_000f
		tracker.sample(null).peakBytesPerSecond shouldBe 3_000f
		tracker.sample(ByteCounterSnapshot("App I/O", 10, 10, 4_000)).peakBytesPerSecond shouldBe null
	}

	test("CPU idle deltas use microseconds and long counters") {
		val previous = IdleCpuSnapshot(1_000, listOf(4_000_000_000L, 5_000_000_000L))
		val current = IdleCpuSnapshot(2_000, listOf(4_000_250_000L, 5_000_750_000L))
		idleCpuPercent(previous, current) shouldBe 50f
		idleCpuPercent(null, current) shouldBe null
	}

	test("offline and newly online cores do not create false CPU spikes") {
		val previous = IdleCpuSnapshot(1_000, listOf(100L, null))
		idleCpuPercent(previous, IdleCpuSnapshot(2_000, listOf(500_100L, null))) shouldBe 25f
		idleCpuPercent(previous, IdleCpuSnapshot(2_000, listOf(500_100L, 100L))) shouldBe null
		idleCpuPercent(previous, IdleCpuSnapshot(2_000, listOf(50L, null))) shouldBe null
		idleCpuPercent(previous, IdleCpuSnapshot(1_000, listOf(100L, null))) shouldBe null
	}

	test("byte usage reports rates and measured totals without mixing sources or gaps") {
		val tracker = ByteUsageTracker()
		tracker.sample(ByteCounterSnapshot("App", 100, 200, 1_000)).readBytesPerSecond shouldBe null
		val next = tracker.sample(ByteCounterSnapshot("App", 2_100, 1_200, 3_000))
		next.readBytesPerSecond shouldBe 1_000f
		next.writeBytesPerSecond shouldBe 500f
		next.readBytes shouldBe 2_000L
		next.writeBytes shouldBe 1_000L
		tracker.sample(null).readBytesPerSecond shouldBe null
		tracker.sample(ByteCounterSnapshot("App", 9_000, 9_000, 5_000)).readBytes shouldBe 2_000L
		val switched = tracker.sample(ByteCounterSnapshot("Device", 99_000, 99_000, 6_000))
		switched.readBytes shouldBe 0L
		switched.readBytesPerSecond shouldBe null
	}

	test("counter resets and invalid intervals rebase byte rates") {
		val tracker = ByteUsageTracker()
		tracker.sample(ByteCounterSnapshot("App", 1_000, 1_000, 1_000))
		tracker.sample(ByteCounterSnapshot("App", 100, 100, 2_000)).readBytesPerSecond shouldBe null
		tracker.sample(ByteCounterSnapshot("App", 200, 200, 2_000)).readBytesPerSecond shouldBe null
		tracker.sample(ByteCounterSnapshot("App", 300, 300, 3_000)).readBytesPerSecond shouldBe 100f
	}

	test("process IO prefers storage counters and labels syscall fallback") {
		val storage = parseProcessIo("rchar: 9999\nwchar: 8888\nread_bytes: 0\nwrite_bytes: 4096", 1_000)
		storage shouldBe ByteCounterSnapshot("App storage", 0, 4096, 1_000)
		parseProcessIo("rchar: 9999\nwchar: 8888", 1_000) shouldBe ByteCounterSnapshot("App I/O", 9999, 8888, 1_000)
		parseProcessIo("read_bytes: -1\nwrite_bytes: 2", 1_000) shouldBe null
		parseProcessIo("read_bytes: 1\nwchar: 2", 1_000) shouldBe null
	}
	test("Java heap readings retain the sampled peak after memory is reclaimed") {
		val first = javaHeapUsage(usedBytes = 200, limitBytes = 512, previousPeakBytes = 0)
		first.usedBytes shouldBe 200L
		first.limitBytes shouldBe 512L
		first.peakBytes shouldBe 200L
		val afterGc = javaHeapUsage(usedBytes = 100, limitBytes = 512, previousPeakBytes = first.peakBytes!!)
		afterGc.usedBytes shouldBe 100L
		afterGc.peakBytes shouldBe 200L
		javaHeapUsage(usedBytes = 300, limitBytes = 512, previousPeakBytes = 200).peakBytes shouldBe 300L
	}

	test("invalid heap limits stay unknown without hiding the measured usage") {
		val sample = javaHeapUsage(usedBytes = 100, limitBytes = 0, previousPeakBytes = 200)
		sample.usedBytes shouldBe 100L
		sample.limitBytes shouldBe null
		sample.peakBytes shouldBe 200L
		javaHeapUsage(usedBytes = -1, limitBytes = -1, previousPeakBytes = -1).usedBytes shouldBe 0L
	}

	test("throughput scale follows the visible peak and handles unavailable samples") {
		throughputGraphScale(listOf(50f, null, 500f)) shouldBe 500f
		throughputGraphScale(listOf(50f, null, 100f)) shouldBe 100f
		throughputGraphScale(emptyList()) shouldBe 1f
		throughputGraphScale(listOf(0f, null, Float.NaN, Float.POSITIVE_INFINITY, -5f)) shouldBe 1f
	}

	test("performance metrics use units defined by their source") {
		64f.asGpuPercent() shouldBe 64f
		101f.asGpuPercent() shouldBe null
		85f.asHardwareTemperatureCelsius() shouldBe 85f
		85_000f.asThermalZoneTemperatureCelsius() shouldBe 85f
	}
})
