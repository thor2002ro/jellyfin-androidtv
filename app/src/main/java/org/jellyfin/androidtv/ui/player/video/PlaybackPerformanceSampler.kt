package org.jellyfin.androidtv.ui.player.video

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

internal class PlaybackPerformanceSampler(context: Context) {
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
	private val activityManager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
	private val appUid = context.applicationInfo.uid
	private val hardwarePropertiesManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
		context.applicationContext.getSystemService("hardware_properties")
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
	private val detectedGpuLabel by lazy { detectGpuLabel() }
	private val detectedGpuTokens by lazy { detectedGpuLabel.toSourceTokens() }

	private val cpuClockTicksPerSecond by lazy {
		runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
			.getOrDefault(100L)
			.takeIf { it > 0 }
			?: 100L
	}

	private val gpuPercentSources by lazy { discoverGpuPercentSources() }
	private val gpuBusySources by lazy { discoverGpuBusySources() }

	fun sample(): PlaybackPerformanceSample = PlaybackPerformanceSample(
		cpu = sampleCpu(),
		gpu = sampleGpu(),
		memory = sampleMemory(),
		network = sampleNetwork(),
	)

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

		val values = line.split(Regex("\\s+"))
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
			.split(Regex("\\s+"))
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
				?.normalizeGpuPercent()
				?.let { percent -> return GpuPercent(source.label, percent) }
			failedGpuPercentSourceKeys += source.sourceKey
			activeGpuPercentSource = null
		}

		if (gpuPercentSourcesExhausted) return null

		val sample = gpuPercentSources.firstNotNullOfOrNull { source ->
			if (source.sourceKey in failedGpuPercentSourceKeys) return@firstNotNullOfOrNull null

			val percent = readFirstNumber(source.file)
				?.normalizeGpuPercent()
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
			?.split(Regex("\\s+"))
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
		discoverSourceFiles(GpuPercentFileNames, GpuSourceDiscoveryDepth)
			.mapNotNull { file ->
				val score = file.gpuSourceScore()
				if (score <= 0) return@mapNotNull null

				if (readFirstNumber(file)?.normalizeGpuPercent() == null) return@mapNotNull null
				GpuPercentSource(
					label = detectedGpuLabel,
					file = file,
					sourceKey = file.sourceKey(),
					score = score,
				)
			}
			.sortedWith(compareByDescending<GpuPercentSource> { source -> source.score }.thenBy { source -> source.file.path })

	private fun discoverGpuBusySources(): List<GpuBusySource> =
		discoverSourceFiles(GpuBusyFileNames, GpuSourceDiscoveryDepth)
			.mapNotNull { file ->
				val score = file.gpuSourceScore()
				if (score <= 0) return@mapNotNull null

				val source = GpuBusySource(
					label = detectedGpuLabel,
					file = file,
					sourceKey = file.sourceKey(),
					score = score,
				)
				if (readGpuBusySnapshot(source) == null) return@mapNotNull null

				source
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
			"gpu_utilization" -> 5
			"utilisation" -> 3
			"utilization" -> 3
			"load" -> 1
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

		val info = ActivityManager.MemoryInfo()
		val manager = activityManager ?: run {
			memorySourceAvailable = false
			return MemoryUsageMetric()
		}
		if (runCatching { manager.getMemoryInfo(info) }.isFailure) {
			memorySourceAvailable = false
			return MemoryUsageMetric()
		}

		val totalBytes = info.totalMem.takeIf { it > 0 } ?: run {
			memorySourceAvailable = false
			return MemoryUsageMetric()
		}
		val usedBytes = (totalBytes - info.availMem).coerceIn(0L, totalBytes)
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
		val zones = runCatching {
			File("/sys/class/thermal")
				.listFiles()
				.orEmpty()
				.filter { file -> file.name.startsWith("thermal_zone") }
				.sortedBy { file -> file.name }
		}.getOrDefault(emptyList())

		val candidates = zones.mapNotNull { zone ->
			val type = runCatching { File(zone, "type").readText().trim() }.getOrNull()
				?: return@mapNotNull null

			val tempFile = File(zone, "temp")
			if (readTemperatureCelsius(tempFile) == null) return@mapNotNull null

			val score = temperatureSourceScore(device, zone, type)
			if (score <= 0) return@mapNotNull null

			TemperatureSource(file = tempFile, sourceKey = tempFile.sourceKey(), score = score)
		}
		val preferredCandidates = candidates
			.filter { source -> source.score >= PreferredTemperatureSourceScore }
			.ifEmpty { candidates }

		return preferredCandidates
			.sortedWith(compareByDescending<TemperatureSource> { source -> source.score }.thenBy { source -> source.file.path })
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
			.mapNotNull { value -> value.normalizeTemperatureCelsius() }
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
		readFirstNumber(file)?.normalizeTemperatureCelsius()

	private fun Float.normalizeGpuPercent(): Float? = when {
		this in 0f..100f -> this
		this in 100f..255f -> this / 255f * 100f
		this in 255f..1_000f -> this / 10f
		else -> null
	}

	private fun Float.normalizeTemperatureCelsius(): Float? {
		val celsius = when {
			!isFinite() -> return null
			this > 1_000f -> this / 1_000f
			this > 200f -> this / 10f
			else -> this
		}

		return celsius.takeIf { it in -50f..200f }
	}

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
		val textTokens = split(Regex("[^A-Za-z0-9]+"))
			.map { token -> token.lowercase() }
		val acronymTokens = split(Regex("[^A-Za-z0-9]+"))
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

	private fun detectGpuLabel(): String =
		runCatching { detectOpenGlRenderer() }
			.getOrNull()
			?.sanitizeGpuLabel()
			?: "GPU"

	private fun detectOpenGlRenderer(): String? {
		val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
		if (display == EGL14.EGL_NO_DISPLAY) return null

		val previousDisplay = EGL14.eglGetCurrentDisplay()
		val previousContext = EGL14.eglGetCurrentContext()
		val previousDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
		val previousReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
		val hadPreviousContext = previousDisplay != EGL14.EGL_NO_DISPLAY && previousContext != EGL14.EGL_NO_CONTEXT
		var initialized = false
		var madeCurrent = false
		var releaseThread = false
		var surface = EGL14.EGL_NO_SURFACE
		var context = EGL14.EGL_NO_CONTEXT
		try {
			initialized = EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)
			if (!initialized) return null

			val configs = arrayOfNulls<EGLConfig>(1)
			val configCount = IntArray(1)
			val configAttributes = intArrayOf(
				EGL14.EGL_RENDERABLE_TYPE,
				EGL14.EGL_OPENGL_ES2_BIT,
				EGL14.EGL_SURFACE_TYPE,
				EGL14.EGL_PBUFFER_BIT,
				EGL14.EGL_RED_SIZE,
				8,
				EGL14.EGL_GREEN_SIZE,
				8,
				EGL14.EGL_BLUE_SIZE,
				8,
				EGL14.EGL_NONE,
			)
			if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0)) return null
			val config = configs.firstOrNull() ?: return null
			if (configCount[0] <= 0) return null

			surface = EGL14.eglCreatePbufferSurface(
				display,
				config,
				intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
				0,
			)
			if (surface == EGL14.EGL_NO_SURFACE) return null

			context = EGL14.eglCreateContext(
				display,
				config,
				EGL14.EGL_NO_CONTEXT,
				intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
				0,
			)
			if (context == EGL14.EGL_NO_CONTEXT) return null
			if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
			madeCurrent = true

			return GLES20.glGetString(GLES20.GL_RENDERER)
		} finally {
			if (initialized) {
				if (madeCurrent) {
					if (hadPreviousContext) {
						if (!EGL14.eglMakeCurrent(previousDisplay, previousDrawSurface, previousReadSurface, previousContext)) {
							EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
						}
					} else {
						EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
						releaseThread = true
					}
				}
				if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
				if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
				if (releaseThread) EGL14.eglReleaseThread()
			}
		}
	}

	private fun String.sanitizeGpuLabel(): String? {
		val clean = trim()
			.replace("(TM)", "")
			.replace(Regex("[(),;]+"), " ")
			.replace(Regex("\\s+"), " ")
			.trim()
			.takeIf { label -> label.isNotBlank() && !label.equals("unknown", ignoreCase = true) }
			?: return null

		val tokens = clean.split(' ')
			.map { token -> token.trim() }
			.filter { token -> token.isNotBlank() }
			.filterNot { token -> token.normalizedGpuRendererToken() in GpuRendererNoiseTokens }
			.filterNot { token -> GpuRendererVersionToken.matches(token.lowercase()) }

		if (tokens.isEmpty()) return clean

		val modelStart = tokens.indexOfFirst { token -> token.isGpuModelToken() }
		val modelTokens = when {
			modelStart > 0 && tokens[modelStart - 1].isGpuVendorToken() -> tokens.drop(modelStart - 1)
			modelStart >= 0 -> tokens.drop(modelStart)
			else -> tokens
		}

		return modelTokens
			.take(GpuRendererLabelMaxWords)
			.joinToString(" ")
			.takeIf { label -> label.isNotBlank() }
			?: clean
	}

	private fun String.normalizedGpuRendererToken() = trim(',', '(', ')')
		.lowercase()
		.removeSuffix(":")

	private fun String.isGpuModelToken(): Boolean {
		val normalized = normalizedGpuRendererToken()
		return isGpuVendorToken() ||
			normalized.any(Char::isDigit) ||
			normalized.startsWith("gc")
	}

	private fun String.isGpuVendorToken() = normalizedGpuRendererToken() in GpuRendererVendorTokens

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
		private const val GpuRendererLabelMaxWords = 4
		private val FirstNumberRegex = Regex("-?\\d+(?:\\.\\d+)?")
		private val GpuRendererVersionToken = Regex("(?:[vrp]\\d+(?:[._-]?[a-z]?\\d+)+.*|\\d+(?:[._-][a-z]?\\d+)+.*)")
		private val GpuRendererVendorTokens = setOf(
			"adreno",
			"immortalis",
			"mali",
			"nvidia",
			"powervr",
			"tegra",
			"vivante",
		)
		private val GpuRendererNoiseTokens = setOf(
			"android",
			"angle",
			"arm",
			"es",
			"google",
			"graphics",
			"inc",
			"llc",
			"opengl",
			"renderer",
			"technologies",
			"vulkan",
		)
		private val GpuPercentFileNames = setOf(
			"busy_percent",
			"gpu_busy_percent",
			"gpu_busy_percentage",
			"gpu_utilization",
			"load",
			"utilisation",
			"utilization",
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
