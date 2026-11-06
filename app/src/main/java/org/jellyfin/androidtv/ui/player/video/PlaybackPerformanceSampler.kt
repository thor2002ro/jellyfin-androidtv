package org.jellyfin.androidtv.ui.player.video

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.jellyfin.androidtv.util.DeviceGraphicsInfo
import org.jellyfin.androidtv.util.DeviceGraphicsInfoProvider
import java.io.File

internal class PlaybackPerformanceSampler(context: Context) {
	private val appContext = context.applicationContext
	private var previousCpuSnapshot: CpuSnapshot? = null
	private var previousProcessCpuSnapshot: ProcessCpuSnapshot? = null
	private var previousGpuBusySnapshot: GpuBusySnapshot? = null
	private var previousNetworkSnapshot: NetworkSnapshot? = null
	private var activeGpuPercentSource: GpuPercentSource? = null
	private var activeGpuBusySource: GpuBusySource? = null
	private val failedGpuPercentSourceKeys = mutableSetOf<String>()
	private val failedGpuBusySourceKeys = mutableSetOf<String>()
	private val failedCpuTemperatureSourceKeys = mutableSetOf<String>()
	private val failedGpuTemperatureSourceKeys = mutableSetOf<String>()
	private var cpuTemperatureSources: List<TemperatureSource>? = null
	private var gpuTemperatureSources: List<TemperatureSource>? = null
	private var systemCpuSourceAvailable = true
	private var processCpuSourceAvailable = true
	private var gpuPercentSourcesExhausted = false
	private var gpuBusySourcesExhausted = false
	private var memorySourceAvailable = true
	private var uidNetworkSourceAvailable = true
	private var totalNetworkSourceAvailable = true
	private var hardwareCpuTemperatureAvailable = true
	private var hardwareGpuTemperatureAvailable = true
	private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
	private val memoryInfo = ActivityManager.MemoryInfo()
	private val appUid = context.applicationInfo.uid
	private val hardwarePropertiesManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
		appContext.getSystemService("hardware_properties")
	} else {
		null
	}
	private val getDeviceTemperaturesMethod by lazy {
		hardwarePropertiesManager
			?.javaClass
			?.methods
			?.firstOrNull { method ->
				method.name == "getDeviceTemperatures" && method.parameterTypes.size == 2
			}
	}
	private lateinit var detectedGraphicsInfo: DeviceGraphicsInfo
	private val detectedGpuRendererLabel by lazy { detectedGraphicsInfo.gpuName }
	private val detectedGpuLabel by lazy { detectedGraphicsInfo.label }
	private val detectedGpuTokens by lazy { detectedGpuRendererLabel.toSourceTokens() }

	private val cpuClockTicksPerSecond by lazy {
		runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
			.getOrDefault(100L)
			.takeIf { it > 0 }
			?: 100L
	}

	private val gpuPercentSources by lazy { discoverGpuPercentSources() }
	private val gpuBusySources by lazy { discoverGpuBusySources() }
	private val gpuSourceFiles by lazy {
		discoverSourceFiles(GpuPercentFileNames + GpuBusyFileNames, GpuSourceDiscoveryDepth)
	}
	private val thermalZones by lazy { discoverThermalZones() }

	suspend fun sample(): PlaybackPerformanceSample {
		if (!::detectedGraphicsInfo.isInitialized) {
			detectedGraphicsInfo = DeviceGraphicsInfoProvider.get()
		}
		return PlaybackPerformanceSample(
			cpu = sampleCpu(),
			gpu = sampleGpu(),
			memory = sampleMemory(),
			network = sampleNetwork(),
		)
	}

	private fun sampleCpu(): UsageMetric {
		val temperatureCelsius = sampleTemperature(TemperatureDevice.CPU)

		readSystemCpuSnapshot()?.let { snapshot ->
			val percent = previousCpuSnapshot
				?.let { previous -> calculateSystemCpuPercent(previous, snapshot) }
			previousCpuSnapshot = snapshot

			return UsageMetric(
				label = "CPU",
				percent = percent,
				temperatureCelsius = temperatureCelsius,
			)
		}

		readProcessCpuSnapshot()?.let { snapshot ->
			val percent = previousProcessCpuSnapshot
				?.let { previous -> calculateProcessCpuPercent(previous, snapshot) }
			previousProcessCpuSnapshot = snapshot

			return UsageMetric(
				label = "App CPU",
				percent = percent,
				temperatureCelsius = temperatureCelsius,
			)
		}

		return UsageMetric(
			label = "CPU",
			percent = null,
			temperatureCelsius = temperatureCelsius,
		)
	}

	private fun readSystemCpuSnapshot(): CpuSnapshot? {
		if (!systemCpuSourceAvailable) return null

		val line = runCatching {
			File("/proc/stat").useLines { lines ->
				lines.firstOrNull { line -> line.startsWith("cpu ") }
			}
		}.getOrNull()
		if (line == null) {
			systemCpuSourceAvailable = false
			return null
		}

		val values = line.split(WhitespaceRegex)
			.drop(1)
			.mapNotNull { value -> value.toLongOrNull() }
		if (values.size < 4) {
			systemCpuSourceAvailable = false
			return null
		}

		val idle = values.getOrNull(3).orZero() + values.getOrNull(4).orZero()
		val total = values.sum()
		if (total <= 0) {
			systemCpuSourceAvailable = false
			return null
		}

		return CpuSnapshot(
			idle = idle,
			total = total,
		)
	}

	private fun readProcessCpuSnapshot(): ProcessCpuSnapshot? {
		if (!processCpuSourceAvailable) return null

		val stat = runCatching { File("/proc/self/stat").readText() }.getOrNull()
		if (stat == null) {
			processCpuSourceAvailable = false
			return null
		}
		val endOfName = stat.lastIndexOf(')')
		if (endOfName == -1 || endOfName + 2 >= stat.length) {
			processCpuSourceAvailable = false
			return null
		}

		val values = stat.substring(endOfName + 2)
			.trim()
			.split(WhitespaceRegex)
		val userTicks = values.getOrNull(11)?.toLongOrNull()
		val systemTicks = values.getOrNull(12)?.toLongOrNull()
		if (userTicks == null || systemTicks == null) {
			processCpuSourceAvailable = false
			return null
		}

		return ProcessCpuSnapshot(
			cpuTimeMs = ((userTicks + systemTicks) * 1_000L) / cpuClockTicksPerSecond,
			elapsedMs = SystemClock.elapsedRealtime(),
		)
	}

	private fun calculateSystemCpuPercent(
		previous: CpuSnapshot,
		current: CpuSnapshot,
	): Float? {
		val totalDelta = current.total - previous.total
		val idleDelta = current.idle - previous.idle
		if (totalDelta <= 0 || idleDelta < 0) return null

		return (((totalDelta - idleDelta).toFloat() / totalDelta.toFloat()) * 100f)
			.coerceIn(0f, 100f)
	}

	private fun calculateProcessCpuPercent(
		previous: ProcessCpuSnapshot,
		current: ProcessCpuSnapshot,
	): Float? {
		val cpuDelta = current.cpuTimeMs - previous.cpuTimeMs
		val elapsedDelta = current.elapsedMs - previous.elapsedMs
		if (cpuDelta < 0 || elapsedDelta <= 0) return null

		val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
		return ((cpuDelta.toFloat() / elapsedDelta.toFloat()) * 100f / coreCount)
			.coerceIn(0f, 100f)
	}

	private fun sampleGpu(): UsageMetric {
		val temperatureCelsius = sampleTemperature(TemperatureDevice.GPU)

		readGpuPercent()?.let { sample ->
			return UsageMetric(
				label = sample.label,
				percent = sample.percent,
				temperatureCelsius = temperatureCelsius,
			)
		}

		readGpuBusySnapshot()?.let { snapshot ->
			val percent = previousGpuBusySnapshot
				?.takeIf { previous -> previous.sourceKey == snapshot.sourceKey }
				?.let { previous -> calculateGpuBusyPercent(previous, snapshot) }
			previousGpuBusySnapshot = snapshot

			return UsageMetric(
				label = snapshot.label,
				percent = percent,
				temperatureCelsius = temperatureCelsius,
			)
		}

		return UsageMetric(
			label = detectedGpuLabel,
			percent = null,
			temperatureCelsius = temperatureCelsius,
		)
	}

	private fun readGpuPercent(): GpuPercent? {
		activeGpuPercentSource?.let { source ->
			readFirstNumber(source.file)
				?.asGpuPercent()
				?.let { percent -> return GpuPercent(source.label, percent) }
			failedGpuPercentSourceKeys += source.sourceKey
			activeGpuPercentSource = null
		}

		if (gpuPercentSourcesExhausted) return null

		val sample = gpuPercentSources.firstNotNullOfOrNull { source ->
			if (source.sourceKey in failedGpuPercentSourceKeys) return@firstNotNullOfOrNull null

			val percent = readFirstNumber(source.file)
				?.asGpuPercent()
			if (percent == null) {
				failedGpuPercentSourceKeys += source.sourceKey
				return@firstNotNullOfOrNull null
			}

			activeGpuPercentSource = source
			GpuPercent(source.label, percent)
		}
		if (sample == null) gpuPercentSourcesExhausted = true

		return sample
	}

	private fun readGpuBusySnapshot(): GpuBusySnapshot? {
		activeGpuBusySource?.let { source ->
			readGpuBusySnapshot(source)?.let { snapshot -> return snapshot }
			failedGpuBusySourceKeys += source.sourceKey
			activeGpuBusySource = null
		}

		if (gpuBusySourcesExhausted) return null

		val snapshot = gpuBusySources.firstNotNullOfOrNull { source ->
			if (source.sourceKey in failedGpuBusySourceKeys) return@firstNotNullOfOrNull null

			val currentSnapshot = readGpuBusySnapshot(source)
			if (currentSnapshot == null) {
				failedGpuBusySourceKeys += source.sourceKey
				return@firstNotNullOfOrNull null
			}

			activeGpuBusySource = source
			currentSnapshot
		}
		if (snapshot == null) gpuBusySourcesExhausted = true

		return snapshot
	}

	private fun readGpuBusySnapshot(source: GpuBusySource): GpuBusySnapshot? {
		val values = runCatching { source.file.readText() }
			.getOrNull()
			?.split(WhitespaceRegex)
			?.mapNotNull { value -> value.toLongOrNull() }
			?: return null
		if (values.size < 2) return null

		val busy = values[0]
		val total = values[1]
		if (busy < 0 || total <= 0) return null

		return GpuBusySnapshot(
			label = source.label,
			sourceKey = source.sourceKey,
			busy = busy,
			total = total,
		)
	}

	private fun discoverGpuPercentSources(): List<GpuPercentSource> =
		gpuSourceFiles
			.filter { file -> file.name in GpuPercentFileNames }
			.mapNotNull { file ->
				val score = file.gpuSourceScore()
				if (score <= 0) return@mapNotNull null

				GpuPercentSource(
					label = detectedGpuLabel,
					file = file,
					sourceKey = file.sourceKey(),
					score = score,
				)
			}
			.sortedWith(compareByDescending<GpuPercentSource> { source -> source.score }.thenBy { source -> source.file.path })

	private fun discoverGpuBusySources(): List<GpuBusySource> =
		gpuSourceFiles
			.filter { file -> file.name in GpuBusyFileNames }
			.mapNotNull { file ->
				val score = file.gpuSourceScore()
				if (score <= 0) return@mapNotNull null

				GpuBusySource(
					label = detectedGpuLabel,
					file = file,
					sourceKey = file.sourceKey(),
					score = score,
				)
			}
			.sortedWith(compareByDescending<GpuBusySource> { source -> source.score }.thenBy { source -> source.file.path })

	private fun File.gpuSourceScore(): Int {
		val hintTokens = sourceHint().toSourceTokens(ignoreCommonTokens = false)
		val rendererScore = detectedGpuTokens.count { token -> token in hintTokens } * 8
		val gpuHintScore = GenericGpuSourceTokens.count { token -> token in hintTokens } * 4
		val fileNameScore = when (name) {
			"gpu_busy_percent" -> 6
			"gpu_busy_percentage" -> 6
			"busy_percent" -> 5
			"gpubusy" -> 5
			else -> 0
		}

		return when {
			rendererScore > 0 || gpuHintScore > 0 -> rendererScore + gpuHintScore + fileNameScore
			else -> 0
		}
	}

	private fun calculateGpuBusyPercent(
		previous: GpuBusySnapshot,
		current: GpuBusySnapshot,
	): Float? {
		val busyDelta = current.busy - previous.busy
		val totalDelta = current.total - previous.total
		if (busyDelta < 0 || totalDelta <= 0) return null

		return ((busyDelta.toFloat() / totalDelta.toFloat()) * 100f)
			.coerceIn(0f, 100f)
	}

	private fun sampleMemory(): MemoryUsageMetric {
		if (!memorySourceAvailable) return MemoryUsageMetric()

		val manager = activityManager ?: run {
			memorySourceAvailable = false
			return MemoryUsageMetric()
		}
		if (runCatching { manager.getMemoryInfo(memoryInfo) }.isFailure) {
			memorySourceAvailable = false
			return MemoryUsageMetric()
		}

		val totalBytes = memoryInfo.totalMem.takeIf { it > 0 } ?: run {
			memorySourceAvailable = false
			return MemoryUsageMetric()
		}
		val usedBytes = (totalBytes - memoryInfo.availMem).coerceIn(0L, totalBytes)
		val percent = (usedBytes.toFloat() / totalBytes.toFloat() * 100f).coerceIn(0f, 100f)

		return MemoryUsageMetric(
			percent = percent,
			usedBytes = usedBytes,
			totalBytes = totalBytes,
		)
	}

	private fun sampleNetwork(): NetworkThroughputMetric {
		val snapshot = readUidNetworkSnapshot() ?: readTotalNetworkSnapshot() ?: return NetworkThroughputMetric()
		val previous = previousNetworkSnapshot?.takeIf { previous -> previous.source == snapshot.source }
		previousNetworkSnapshot = snapshot

		if (previous == null) return NetworkThroughputMetric()

		val elapsedMs = snapshot.elapsedRealtimeMs - previous.elapsedRealtimeMs
		val receivedBytes = snapshot.receivedBytes - previous.receivedBytes
		val transmittedBytes = snapshot.transmittedBytes - previous.transmittedBytes
		if (elapsedMs <= 0 || receivedBytes < 0L || transmittedBytes < 0L) return NetworkThroughputMetric()

		val elapsedSeconds = elapsedMs.toFloat() / 1_000f
		return NetworkThroughputMetric(
			downloadBytesPerSecond = receivedBytes.toFloat() / elapsedSeconds,
			uploadBytesPerSecond = transmittedBytes.toFloat() / elapsedSeconds,
		)
	}

	private fun readUidNetworkSnapshot(): NetworkSnapshot? {
		if (!uidNetworkSourceAvailable) return null

		val receivedBytes = TrafficStats.getUidRxBytes(appUid)
		val transmittedBytes = TrafficStats.getUidTxBytes(appUid)
		if (receivedBytes == TrafficStats.UNSUPPORTED.toLong() || transmittedBytes == TrafficStats.UNSUPPORTED.toLong()) {
			uidNetworkSourceAvailable = false
			return null
		}

		return NetworkSnapshot(
			source = NetworkSnapshotSource.APP,
			receivedBytes = receivedBytes,
			transmittedBytes = transmittedBytes,
			elapsedRealtimeMs = SystemClock.elapsedRealtime(),
		)
	}

	private fun readTotalNetworkSnapshot(): NetworkSnapshot? {
		if (!totalNetworkSourceAvailable) return null

		val receivedBytes = TrafficStats.getTotalRxBytes()
		val transmittedBytes = TrafficStats.getTotalTxBytes()
		if (receivedBytes == TrafficStats.UNSUPPORTED.toLong() || transmittedBytes == TrafficStats.UNSUPPORTED.toLong()) {
			totalNetworkSourceAvailable = false
			return null
		}

		return NetworkSnapshot(
			source = NetworkSnapshotSource.DEVICE,
			receivedBytes = receivedBytes,
			transmittedBytes = transmittedBytes,
			elapsedRealtimeMs = SystemClock.elapsedRealtime(),
		)
	}

	private fun sampleTemperature(device: TemperatureDevice): Float? =
		sampleHardwareTemperature(device) ?: sampleSysfsTemperature(device)

	private fun sampleSysfsTemperature(device: TemperatureDevice): Float? {
		val sources = when (device) {
			TemperatureDevice.CPU -> cpuTemperatureSources ?: discoverTemperatureSources(device)
				.also { sources -> cpuTemperatureSources = sources }

			TemperatureDevice.GPU -> gpuTemperatureSources ?: discoverTemperatureSources(device)
				.also { sources -> gpuTemperatureSources = sources }
		}
		val failedSourceKeys = failedTemperatureSourceKeys(device)

		return sources
			.asSequence()
			.filterNot { source -> source.sourceKey in failedSourceKeys }
			.mapNotNull { source ->
				val temperature = readTemperatureCelsius(source.file)
				if (temperature == null) {
					failedSourceKeys += source.sourceKey
					return@mapNotNull null
				}

				temperature
			}
			.maxOrNull()
	}

	private fun failedTemperatureSourceKeys(device: TemperatureDevice): MutableSet<String> =
		when (device) {
			TemperatureDevice.CPU -> failedCpuTemperatureSourceKeys
			TemperatureDevice.GPU -> failedGpuTemperatureSourceKeys
		}

	private fun discoverTemperatureSources(device: TemperatureDevice): List<TemperatureSource> {
		val candidates = thermalZones.mapNotNull { zone ->
			val score = temperatureSourceScore(device, zone.directory, zone.type)
			if (score <= 0) return@mapNotNull null

			TemperatureSource(
				file = zone.temperatureFile,
				sourceKey = zone.temperatureFile.sourceKey(),
				score = score,
			)
		}
		val preferredCandidates = candidates
			.filter { source -> source.score >= PreferredTemperatureSourceScore }
			.ifEmpty { candidates }

		return preferredCandidates
			.sortedWith(compareByDescending<TemperatureSource> { source -> source.score }.thenBy { source -> source.file.path })
	}

	private fun discoverThermalZones(): List<ThermalZone> {
		val directories = runCatching {
			File("/sys/class/thermal")
				.listFiles()
				.orEmpty()
				.filter { file -> file.name.startsWith("thermal_zone") }
				.sortedBy { file -> file.name }
		}.getOrDefault(emptyList())

		return directories.mapNotNull { directory ->
			val type = runCatching { File(directory, "type").readText().trim() }.getOrNull()
				?: return@mapNotNull null
			ThermalZone(
				directory = directory,
				type = type,
				temperatureFile = File(directory, "temp"),
			)
		}
	}

	private fun sampleHardwareTemperature(device: TemperatureDevice): Float? {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
		if (hardwarePropertiesManager == null) return null
		if (device == TemperatureDevice.CPU && !hardwareCpuTemperatureAvailable) return null
		if (device == TemperatureDevice.GPU && !hardwareGpuTemperatureAvailable) return null

		val method = getDeviceTemperaturesMethod ?: run {
			markHardwareTemperatureUnavailable(device)
			return null
		}
		val values = runCatching {
			method.invoke(hardwarePropertiesManager, device.hardwareDeviceType, HardwareTemperatureSourceCurrent)
				as? FloatArray
		}.getOrElse {
			markHardwareTemperatureUnavailable(device)
			return null
		}
		if (values == null || values.isEmpty()) {
			markHardwareTemperatureUnavailable(device)
			return null
		}

		val temperature = values
			.asSequence()
			.mapNotNull { value -> value.asHardwareTemperatureCelsius() }
			.maxOrNull()
		if (temperature == null) markHardwareTemperatureUnavailable(device)

		return temperature
	}

	private fun markHardwareTemperatureUnavailable(device: TemperatureDevice) {
		when (device) {
			TemperatureDevice.CPU -> hardwareCpuTemperatureAvailable = false
			TemperatureDevice.GPU -> hardwareGpuTemperatureAvailable = false
		}
	}

	private fun readFirstNumber(file: File): Float? = runCatching {
		file.readText()
	}.getOrNull()
		?.let(FirstNumberRegex::find)
		?.value
		?.toFloatOrNull()

	private fun readTemperatureCelsius(file: File): Float? =
		readFirstNumber(file)?.asThermalZoneTemperatureCelsius()

	private fun temperatureSourceScore(
		device: TemperatureDevice,
		zone: File,
		type: String,
	): Int {
		val hint = buildString {
			append(type)
			append(' ')
			append(zone.absolutePath)
			append(' ')
			runCatching { zone.canonicalPath }.getOrNull()?.let { path ->
				append(path)
				append(' ')
			}
		}.toSourceTokens(ignoreCommonTokens = false)

		return when (device) {
			TemperatureDevice.CPU -> {
				val directScore = CpuTemperatureTokens.count { token -> token in hint } * 8
				val fallbackScore = CpuFallbackTemperatureTokens.count { token -> token in hint } * 2
				directScore + fallbackScore
			}

			TemperatureDevice.GPU -> {
				val rendererScore = detectedGpuTokens.count { token -> token in hint } * 8
				val gpuHintScore = GenericGpuSourceTokens.count { token -> token in hint } * 4
				rendererScore + gpuHintScore
			}
		}
	}

	private fun discoverSourceFiles(
		fileNames: Set<String>,
		maxDepth: Int,
	): List<File> {
		val sources = mutableListOf<File>()
		val visitedDirectories = mutableSetOf<String>()

		fun visit(directory: File, depth: Int) {
			if (depth < 0) return
			val directoryKey = directory.sourceKey()
			if (!visitedDirectories.add(directoryKey)) return

			val children = runCatching { directory.listFiles().orEmpty().toList() }.getOrDefault(emptyList())
			children.forEach { child ->
				when {
					child.name in fileNames -> sources += child
					depth > 0 && child.isDirectory -> visit(child, depth - 1)
				}
			}
		}

		GpuSourceRoots
			.filter { root -> root.exists() && root.isDirectory }
			.forEach { root -> visit(root, maxDepth) }

		return sources.distinctBy { file -> file.sourceKey() }
	}

	private fun File.sourceKey(): String =
		runCatching { canonicalPath }.getOrDefault(absolutePath)

	private fun File.sourceHint(): String = buildString {
		append(absolutePath)
		append(' ')
		runCatching { canonicalPath }.getOrNull()?.let { path ->
			append(path)
			append(' ')
		}

		parentFile?.let { parent ->
			appendReadableText(File(parent, "name"))
			appendReadableText(File(parent, "type"))
			appendReadableText(File(parent, "uevent"))
			appendReadableText(File(parent, "device/name"))
			appendReadableText(File(parent, "device/type"))
			appendReadableText(File(parent, "device/uevent"))
			appendReadableText(File(parent, "device/of_node/name"))
			appendReadableText(File(parent, "device/of_node/compatible"))
		}
	}

	private fun StringBuilder.appendReadableText(file: File) {
		file.readTrimmedText()?.let { text ->
			append(text)
			append(' ')
		}
	}

	private fun File.readTrimmedText(): String? =
		runCatching { readText().trim().take(SysfsHintMaxLength) }
			.getOrNull()
			?.takeIf { text -> text.isNotBlank() }

	private fun String.toSourceTokens(ignoreCommonTokens: Boolean = true): Set<String> {
		val sourceTokens = split(SourceTokenSeparatorRegex)
		val textTokens = sourceTokens
			.map { token -> token.lowercase() }
		val acronymTokens = sourceTokens
			.mapNotNull { token ->
				token.filter(Char::isUpperCase)
					.lowercase()
					.takeIf { acronym -> acronym.length >= 2 }
			}

		return (textTokens + acronymTokens)
			.asSequence()
			.map { token -> token.trim() }
			.filter { token -> token.length >= 2 }
			.filterNot { token -> ignoreCommonTokens && token in IgnoredGpuSourceTokens }
			.toSet()
	}

	private fun Long?.orZero() = this ?: 0L

	private data class CpuSnapshot(
		val idle: Long,
		val total: Long,
	)

	private data class ProcessCpuSnapshot(
		val cpuTimeMs: Long,
		val elapsedMs: Long,
	)

	private data class GpuPercentSource(
		val label: String,
		val file: File,
		val sourceKey: String,
		val score: Int,
	)

	private data class GpuBusySource(
		val label: String,
		val file: File,
		val sourceKey: String,
		val score: Int,
	)

	private data class GpuPercent(
		val label: String,
		val percent: Float,
	)

	private data class GpuBusySnapshot(
		val label: String,
		val sourceKey: String,
		val busy: Long,
		val total: Long,
	)

	private data class NetworkSnapshot(
		val source: NetworkSnapshotSource,
		val receivedBytes: Long,
		val transmittedBytes: Long,
		val elapsedRealtimeMs: Long,
	)

	private data class TemperatureSource(
		val file: File,
		val sourceKey: String,
		val score: Int,
	)

	private data class ThermalZone(
		val directory: File,
		val type: String,
		val temperatureFile: File,
	)

	private enum class NetworkSnapshotSource {
		APP,
		DEVICE,
	}

	private enum class TemperatureDevice(
		val hardwareDeviceType: Int,
	) {
		CPU(0),
		GPU(1),
	}

	private companion object {
		private const val HardwareTemperatureSourceCurrent = 0
		private const val GpuSourceDiscoveryDepth = 4
		private const val PreferredTemperatureSourceScore = 8
		private const val SysfsHintMaxLength = 512
		private val FirstNumberRegex = Regex("-?\\d+(?:\\.\\d+)?")
		private val SourceTokenSeparatorRegex = Regex("[^A-Za-z0-9]+")
		private val WhitespaceRegex = Regex("\\s+")
		private val GpuPercentFileNames = setOf(
			"busy_percent",
			"gpu_busy_percent",
			"gpu_busy_percentage",
		)
		private val GpuBusyFileNames = setOf("gpubusy")
		private val GpuSourceRoots = listOf(
			File("/sys/class/devfreq"),
			File("/sys/class/drm"),
			File("/sys/class/gpu"),
			File("/sys/class/kgsl"),
			File("/sys/class/misc"),
		)
		private val GenericGpuSourceTokens = setOf(
			"gpu",
			"graphics",
			"kgsl",
			"render",
		)
		private val CpuTemperatureTokens = setOf(
			"big",
			"cluster",
			"core",
			"cpu",
			"little",
		)
		private val CpuFallbackTemperatureTokens = setOf(
			"package",
			"soc",
			"tsens",
		)
		private val IgnoredGpuSourceTokens = setOf(
			"android",
			"gpu",
			"renderer",
			"tm",
		)
	}
}

internal fun Float.asGpuPercent(): Float? =
	takeIf { value -> value.isFinite() && value in 0f..100f }

internal fun Float.asHardwareTemperatureCelsius(): Float? =
	takeIf { value -> value.isFinite() && value in -50f..200f }

internal fun Float.asThermalZoneTemperatureCelsius(): Float? =
	(this / 1_000f).takeIf { value -> value.isFinite() && value in -50f..200f }

internal data class PlaybackPerformanceSample(
	val cpu: UsageMetric,
	val gpu: UsageMetric,
	val memory: MemoryUsageMetric,
	val network: NetworkThroughputMetric,
)

internal data class UsageMetric(
	val label: String,
	val percent: Float?,
	val temperatureCelsius: Float? = null,
)

internal data class MemoryUsageMetric(
	val label: String = "RAM",
	val percent: Float? = null,
	val usedBytes: Long? = null,
	val totalBytes: Long? = null,
)

internal data class NetworkThroughputMetric(
	val downloadLabel: String = "Down",
	val uploadLabel: String = "Up",
	val downloadBytesPerSecond: Float? = null,
	val uploadBytesPerSecond: Float? = null,
)
