package org.jellyfin.androidtv.ui.player.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.design.Tokens
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.model.PlaybackFrameStats

private const val UsageGraphSampleCount = 30
private const val RamGraphSampleCount = UsageGraphSampleCount * 2
private const val CpuGraphSampleCount = UsageGraphSampleCount * 3 / 2
private const val FullWidthCpuGraphSampleCount = UsageGraphSampleCount * 2
private const val DecoderGraphFallbackFrameRate = 60f
private const val KIB_BYTES = 1_024.0
private const val MIB_BYTES = 1_048_576.0
private const val GIB_BYTES = 1_073_741_824.0
private val GraphHeaderLineHeight = 8.sp
private val MemoryWarningColor = Tokens.Color.colorRed400

internal fun throughputGraphScale(history: List<Float?>): Float =
	(history.maxOfOrNull { it?.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f } ?: 0f).coerceAtLeast(1f)

internal fun UsageMetric.hasCpuCoreUsage(): Boolean = corePercents.values.any { it?.isFinite() == true }

@Composable
internal fun PlaybackPerformanceOverlay(
	playbackManager: PlaybackManager,
	stream: MediaStream,
	frameStats: PlaybackFrameStats,
) {
	val context = LocalContext.current
	val videoTrack = stream.tracks.filterIsInstance<MediaStreamVideoTrack>().firstOrNull()
	val expectedDecoderFrameRate = videoTrack?.realFrameRate?.takeIf { it > 0f }
	val latestFrameStats by rememberUpdatedState(frameStats)
	val performanceSampler = remember(context.applicationContext) {
		PlaybackPerformanceSampler(context.applicationContext)
	}
	val renderFrameSampler = remember(context) {
		context.findPlaybackActivity()
			?.window
			?.let(::createPlaybackRenderFrameSampler)
	}
	var decoderActivity by remember(stream.identifier) { mutableStateOf(DecoderActivityMetric()) }
	var renderFrameMetric by remember { mutableStateOf(RenderFrameMetric()) }
	var previousDecoderSnapshot by remember(stream.identifier) {
		mutableStateOf<DecoderFrameSnapshot?>(null)
	}
	var performanceSample by remember {
		mutableStateOf(
			PlaybackPerformanceSample(
				cpu = UsageMetric(label = "CPU", percent = null),
				gpu = UsageMetric(label = "GPU", percent = null),
				memory = MemoryUsageMetric(),
				network = NetworkThroughputMetric(),
			)
		)
	}
	var cpuUsageHistory by remember { mutableStateOf<List<Float?>>(emptyList()) }
	var gpuUsageHistory by remember { mutableStateOf<List<Float?>>(emptyList()) }
	var ramUsageHistory by remember { mutableStateOf<List<Float?>>(emptyList()) }
	var networkDownloadHistory by remember { mutableStateOf<List<Float?>>(emptyList()) }
	var networkUploadHistory by remember { mutableStateOf<List<Float?>>(emptyList()) }
	var ioUsageHistory by remember { mutableStateOf<List<Float?>>(emptyList()) }
	var decoderUsageHistory by remember(stream.identifier) { mutableStateOf<List<Float?>>(emptyList()) }
	var renderUsageHistory by remember { mutableStateOf<List<Float?>>(emptyList()) }

	DisposableEffect(renderFrameSampler) {
		renderFrameSampler?.start()
		onDispose {
			renderFrameSampler?.stop()
		}
	}

	LaunchedEffect(stream.identifier, expectedDecoderFrameRate, renderFrameSampler) {
		while (true) {
			val currentFrameStats = latestFrameStats
			val currentDecoderSnapshot = DecoderFrameSnapshot(
				decodedFrames = currentFrameStats.videoDecodedFrames,
				elapsedRealtimeMs = SystemClock.elapsedRealtime(),
			)
			decoderActivity = calculateDecoderActivity(
				previous = previousDecoderSnapshot,
				current = currentDecoderSnapshot,
				expectedFrameRate = expectedDecoderFrameRate,
				measuredFrameRate = currentFrameStats.videoDecoderFps,
			)
			renderFrameMetric = renderFrameSampler?.sample() ?: RenderFrameMetric()
			previousDecoderSnapshot = currentDecoderSnapshot
			decoderUsageHistory = decoderUsageHistory.appendUsageSample(decoderActivity.percent)
			renderUsageHistory = renderUsageHistory.appendUsageSample(renderFrameMetric.percent)

			val network = playbackManager.getService<PlaybackNetworkUsageService>()?.samples?.value ?: NetworkThroughputMetric()
			val sample = withContext(Dispatchers.IO) { performanceSampler.sample(network) }
			performanceSample = sample
			cpuUsageHistory = cpuUsageHistory.appendUsageSample(sample.cpu.percent, FullWidthCpuGraphSampleCount)
			gpuUsageHistory = gpuUsageHistory.appendUsageSample(sample.gpu.percent)
			ramUsageHistory = ramUsageHistory.appendUsageSample(sample.memory.percent, RamGraphSampleCount)
			networkDownloadHistory = networkDownloadHistory.appendUsageSample(sample.network.downloadBytesPerSecond)
			networkUploadHistory = networkUploadHistory.appendUsageSample(sample.network.uploadBytesPerSecond)
			ioUsageHistory = ioUsageHistory.appendUsageSample(sample.io.totalBytesPerSecond())
			delay(1_000)
		}
	}

	PlaybackPerformanceGraphAddon(
		sample = performanceSample,
		decoderActivity = decoderActivity,
		renderFrameMetric = renderFrameMetric,
		cpuUsageHistory = cpuUsageHistory,
		gpuUsageHistory = gpuUsageHistory,
		ramUsageHistory = ramUsageHistory,
		networkDownloadHistory = networkDownloadHistory,
		networkUploadHistory = networkUploadHistory,
		ioUsageHistory = ioUsageHistory,
		decoderUsageHistory = decoderUsageHistory,
		renderUsageHistory = renderUsageHistory,
	)
}

@Composable
private fun PlaybackPerformanceGraphAddon(
	sample: PlaybackPerformanceSample,
	decoderActivity: DecoderActivityMetric,
	renderFrameMetric: RenderFrameMetric,
	cpuUsageHistory: List<Float?>,
	gpuUsageHistory: List<Float?>,
	ramUsageHistory: List<Float?>,
	networkDownloadHistory: List<Float?>,
	networkUploadHistory: List<Float?>,
	ioUsageHistory: List<Float?>,
	decoderUsageHistory: List<Float?>,
	renderUsageHistory: List<Float?>,
) {
	Column(
		modifier = Modifier.width(146.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		if (sample.cpu.temperatureCelsius != null || sample.gpu.temperatureCelsius != null) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				sample.cpu.temperatureCelsius?.let { temperature ->
					PlaybackTemperatureOverlay(
						label = "CPU",
						temperatureCelsius = temperature,
						modifier = Modifier.weight(1f),
					)
				}
				sample.gpu.temperatureCelsius?.let { temperature ->
					PlaybackTemperatureOverlay(
						label = "GPU",
						temperatureCelsius = temperature,
						modifier = Modifier.weight(1f),
					)
				}
			}
		}

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.background(Color.Black.copy(alpha = 0.82f))
				.padding(horizontal = 5.dp, vertical = 4.dp),
			verticalArrangement = Arrangement.spacedBy(3.dp),
		) {
			PlaybackPerformanceGraphs(
				sample = sample,
				decoderActivity = decoderActivity,
				renderFrameMetric = renderFrameMetric,
				cpuUsageHistory = cpuUsageHistory,
				gpuUsageHistory = gpuUsageHistory,
				ramUsageHistory = ramUsageHistory,
				networkDownloadHistory = networkDownloadHistory,
				networkUploadHistory = networkUploadHistory,
				ioUsageHistory = ioUsageHistory,
				decoderUsageHistory = decoderUsageHistory,
				renderUsageHistory = renderUsageHistory,
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

@Composable
private fun PlaybackTemperatureOverlay(
	label: String,
	temperatureCelsius: Float,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.background(Color.Black.copy(alpha = 0.82f))
			.padding(horizontal = 5.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		PlaybackInfoGraphText(
			text = label,
			modifier = Modifier.weight(1f),
		)
		PlaybackInfoGraphText(
			text = temperatureCelsius.formatTemperature(),
			modifier = Modifier.weight(1f),
			textAlign = TextAlign.End,
		)
	}
}

@Composable
private fun PlaybackPerformanceGraphs(
	sample: PlaybackPerformanceSample,
	decoderActivity: DecoderActivityMetric,
	renderFrameMetric: RenderFrameMetric,
	cpuUsageHistory: List<Float?>,
	gpuUsageHistory: List<Float?>,
	ramUsageHistory: List<Float?>,
	networkDownloadHistory: List<Float?>,
	networkUploadHistory: List<Float?>,
	ioUsageHistory: List<Float?>,
	decoderUsageHistory: List<Float?>,
	renderUsageHistory: List<Float?>,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		PlaybackUsageGraph(
			metric = sample.gpu,
			history = gpuUsageHistory,
			color = Tokens.Color.colorPurple400,
			labelOnly = sample.gpu.percent == null,
		)
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			PlaybackUsageGraph(
				metric = UsageMetric(label = "Decoder", percent = decoderActivity.percent),
				history = decoderUsageHistory,
				color = Tokens.Color.colorGreen300,
				valueText = decoderActivity.formatDecoderActivity(),
				modifier = Modifier.weight(1f),
			)
			PlaybackUsageGraph(
				metric = UsageMetric(label = renderFrameMetric.label, percent = renderFrameMetric.percent),
				history = renderUsageHistory,
				color = Tokens.Color.colorCyan300,
				valueText = renderFrameMetric.formatRenderFrameMetric(),
				modifier = Modifier.weight(1f),
			)
		}
		val showCpuCores = sample.cpu.hasCpuCoreUsage()
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			PlaybackUsageGraph(
				metric = sample.cpu,
				history = cpuUsageHistory,
				color = Tokens.Color.colorCyan500,
				modifier = Modifier.weight(3f),
				sampleCount = if (showCpuCores) CpuGraphSampleCount else FullWidthCpuGraphSampleCount,
				headerMinLines = 1,
			)
			if (showCpuCores) PlaybackCpuCoreBars(sample.cpu.corePercents, modifier = Modifier.weight(1f))
		}
		PlaybackUsageGraph(
			metric = UsageMetric(label = sample.memory.label, percent = sample.memory.percent),
			history = ramUsageHistory,
			color = if (sample.memory.isAtLowMemoryThreshold()) MemoryWarningColor else Tokens.Color.colorOrange300,
			valueColor = if (sample.memory.isAtLowMemoryThreshold()) MemoryWarningColor else Color.White,
			valueText = sample.memory.formatMemoryUsage(),
			detailText = "Free ${(sample.memory.totalBytes?.let { total -> sample.memory.usedBytes?.let { total - it } }).formatDataAmount()}",
			sampleCount = RamGraphSampleCount,
		)
		PlaybackJavaHeapDetails(sample.javaHeap)
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			PlaybackNetworkGraph(
				network = sample.network,
				downloadHistory = networkDownloadHistory,
				uploadHistory = networkUploadHistory,
				modifier = Modifier.weight(1f),
			)
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
				PlaybackThroughputGraph(
					label = "I/O",
					bytesPerSecond = sample.io.totalBytesPerSecond(),
					history = ioUsageHistory,
					color = Tokens.Color.colorPurple200,
					valueText = sample.io.totalBytesPerSecond().formatIoRate(),
				)
				PlaybackResourceRow("Read", sample.io.readBytesPerSecond.formatIoRate())
				PlaybackResourceRow("Write", sample.io.writeBytesPerSecond.formatIoRate())
				PlaybackResourceRow("Peak", sample.io.peakBytesPerSecond.formatIoRate())
			}
		}
	}
}

@Composable
private fun PlaybackCpuCoreBars(cores: Map<Int, Float?>, modifier: Modifier = Modifier) {
	val headerHeight = with(LocalDensity.current) { GraphHeaderLineHeight.toDp() }
	if (cores.isEmpty()) {
		Column(modifier = modifier) {
			PlaybackInfoGraphText("CPU cores")
			PlaybackInfoGraphText("n/a")
		}
		return
	}
	BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
		val coreSpacing = 0.5.dp
		val coreWidth = (maxWidth.value - coreSpacing.value * (cores.size - 1)) / cores.size
		val coreFontSize = (coreWidth / (4 * 0.62f)).coerceIn(1f, 5.5f).sp
		Row(horizontalArrangement = Arrangement.spacedBy(coreSpacing), modifier = Modifier.fillMaxWidth()) {
			cores.toSortedMap().values.forEach { percent ->
				Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
					Box(modifier = Modifier.fillMaxWidth().height(headerHeight), contentAlignment = Alignment.Center) {
						PlaybackInfoGraphText(
							percent.formatUsagePercent(),
							modifier = Modifier.fillMaxWidth(),
							textAlign = TextAlign.Center,
							fontSize = coreFontSize,
							maxLines = 1,
						)
					}
					Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
						drawRect(Color.White.copy(alpha = 0.08f))
						val fraction = percent?.takeIf(Float::isFinite)?.div(100f)?.coerceIn(0f, 1f) ?: return@Canvas
						val height = size.height * fraction
						drawRect(Tokens.Color.colorCyan500, topLeft = Offset(0f, size.height - height), size = Size(size.width, height))
					}
				}
			}
		}
	}
}

@Composable
private fun PlaybackResourceRow(label: String, value: String, valueColor: Color = Color.White) {
	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
		PlaybackInfoGraphText(label, modifier = Modifier.weight(1.15f))
		PlaybackInfoGraphText(value, modifier = Modifier.weight(1.45f), textAlign = TextAlign.End, color = valueColor)
	}
}

@Composable
private fun PlaybackJavaHeapDetails(heap: JavaHeapUsageMetric) {
	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
		PlaybackInfoGraphText("Java heap", maxLines = 1)
		PlaybackInfoGraphText(
			text = "${heap.usedBytes?.formatRamAmount() ?: "n/a"}/${heap.limitBytes?.formatRamAmount() ?: "n/a"} " +
				"(avg ${heap.averageBytes?.formatRamAmount() ?: "n/a"} | max ${heap.peakBytes?.formatRamAmount() ?: "n/a"})",
			modifier = Modifier.weight(1f),
			textAlign = TextAlign.End,
			maxLines = 1,
		)
	}
}

@Composable
private fun PlaybackNetworkGraph(
	network: NetworkThroughputMetric,
	downloadHistory: List<Float?>,
	uploadHistory: List<Float?>,
	modifier: Modifier = Modifier,
) {
	val targetScale = remember(downloadHistory, uploadHistory) { throughputGraphScale(downloadHistory + uploadHistory) }
	val animatedScale = animateFloatAsState(targetScale, tween(600, easing = FastOutSlowInEasing), label = "networkScale")
	val downloadColor = Tokens.Color.colorBlue300
	val uploadColor = Tokens.Color.colorYellow300
	val data = network.dataUsage
	val totalRate = network.downloadBytesPerSecond?.let { down -> network.uploadBytesPerSecond?.let { up -> down + up } }
	Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
		PlaybackUsageGraph(
			metric = UsageMetric("NET", percent = null),
			history = downloadHistory,
			color = downloadColor,
			valueText = totalRate.formatThroughput(),
			maxValue = { animatedScale.value },
			secondaryHistory = uploadHistory,
			secondaryColor = uploadColor,
			headerMinLines = 1,
		)
		PlaybackResourceRow("Down", network.downloadBytesPerSecond.formatThroughput(), valueColor = downloadColor)
		PlaybackResourceRow("Up", network.uploadBytesPerSecond.formatThroughput(), valueColor = uploadColor)
		PlaybackResourceRow("Received", data.readBytes.formatDataAmount(), valueColor = downloadColor)
		PlaybackResourceRow("Sent", data.writeBytes.formatDataAmount(), valueColor = uploadColor)
	}
}

@Composable
private fun PlaybackThroughputGraph(
	label: String,
	bytesPerSecond: Float?,
	history: List<Float?>,
	color: Color,
	modifier: Modifier = Modifier,
	valueText: String = bytesPerSecond.formatThroughput(),
) {
	val targetScale = remember(history) { throughputGraphScale(history) }
	val animatedScale = animateFloatAsState(
		targetValue = targetScale,
		animationSpec = tween(600, easing = FastOutSlowInEasing),
		label = "throughputScale",
	)

	PlaybackUsageGraph(
		metric = UsageMetric(label = label, percent = null),
		history = history,
		color = color,
		valueText = valueText,
		modifier = modifier,
		maxValue = { animatedScale.value },
		headerMinLines = 1,
	)
}

@Composable
private fun PlaybackUsageGraph(
	metric: UsageMetric,
	history: List<Float?>,
	color: Color,
	modifier: Modifier = Modifier,
	valueText: String = metric.formatUsageValue(),
	detailText: String? = null,
	valueColor: Color = Color.White,
	labelOnly: Boolean = false,
	maxValue: () -> Float = { 100f },
	sampleCount: Int = UsageGraphSampleCount,
	secondaryHistory: List<Float?>? = null,
	secondaryColor: Color = color,
	headerMinLines: Int = if (detailText != null) 1 else 2,
) {
	val headerHeight = with(LocalDensity.current) { GraphHeaderLineHeight.toDp() } * headerMinLines
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(if (detailText != null) 0.dp else 1.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth().height(headerHeight),
			horizontalArrangement = Arrangement.spacedBy(3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			PlaybackInfoGraphText(
				text = metric.label,
				modifier = Modifier.weight(1.15f),
				minLines = headerMinLines,
			)
			if (!labelOnly) {
				PlaybackInfoGraphText(
					text = valueText,
					modifier = Modifier.weight(1.45f),
					textAlign = TextAlign.End,
					minLines = headerMinLines,
					color = valueColor,
				)
			}
		}

		if (labelOnly) return@Column
		detailText?.let { text ->
			PlaybackInfoGraphText(
				text,
				modifier = Modifier.fillMaxWidth(),
				textAlign = TextAlign.End,
				color = valueColor,
				maxLines = 1,
			)
		}

		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.height(14.dp),
		) {
			val cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
			drawRoundRect(
				color = Color.White.copy(alpha = 0.08f),
				cornerRadius = cornerRadius,
			)
			drawLine(
				color = Color.White.copy(alpha = 0.12f),
				start = Offset(0f, size.height / 2f),
				end = Offset(size.width, size.height / 2f),
				strokeWidth = 1.dp.toPx(),
			)

			val slotWidth = size.width / sampleCount
			val barWidth = maxOf(1f, slotWidth - 1.dp.toPx()) / if (secondaryHistory == null) 1 else 2
			val scale = maxValue().coerceAtLeast(1f)
			fun drawHistory(values: List<Float?>, seriesColor: Color, offset: Float) {
				val samples = values.takeLast(sampleCount)
				val startIndex = sampleCount - samples.size
				samples.forEachIndexed { index, percent ->
					val value = percent?.takeIf(Float::isFinite)?.let { (it / scale).coerceIn(0f, 1f) } ?: return@forEachIndexed
					val barHeight = maxOf(1.dp.toPx(), size.height * value)
					val x = (startIndex + index) * slotWidth + offset
					drawRoundRect(
						color = seriesColor.copy(alpha = 0.7f),
						topLeft = Offset(x = x, y = size.height - barHeight),
						size = Size(width = barWidth, height = barHeight),
						cornerRadius = cornerRadius,
					)
				}
			}
			drawHistory(history, color, 0f)
			secondaryHistory?.let { drawHistory(it, secondaryColor, barWidth) }
		}
	}
}

@Composable
private fun PlaybackInfoGraphText(
	text: String,
	modifier: Modifier = Modifier,
	textAlign: TextAlign = TextAlign.Start,
	minLines: Int = 1,
	color: Color = Color.White,
	fontSize: TextUnit = if (textAlign == TextAlign.End) 5.5.sp else 6.sp,
	maxLines: Int = 2,
) {
	Text(
		text = text,
		modifier = modifier,
		textAlign = textAlign,
		softWrap = true,
		minLines = minLines,
		maxLines = maxLines,
		overflow = TextOverflow.Ellipsis,
		style = TextStyle(
			color = color,
			fontSize = fontSize,
			lineHeight = 6.5.sp,
			fontFamily = FontFamily.Monospace,
			fontWeight = FontWeight.W700,
		),
	)
}

private fun calculateDecoderActivity(
	previous: DecoderFrameSnapshot?,
	current: DecoderFrameSnapshot,
	expectedFrameRate: Float?,
	measuredFrameRate: Float? = null,
): DecoderActivityMetric {
	val fps = measuredFrameRate?.takeIf { it.isFinite() && it >= 0f } ?: run {
		if (previous == null) return DecoderActivityMetric()
		val frameDelta = current.decodedFrames - previous.decodedFrames
		val elapsedDeltaMs = current.elapsedRealtimeMs - previous.elapsedRealtimeMs
		if (frameDelta < 0 || elapsedDeltaMs <= 0) return DecoderActivityMetric()
		frameDelta.toFloat() * 1_000f / elapsedDeltaMs.toFloat()
	}
	val baseline = expectedFrameRate ?: DecoderGraphFallbackFrameRate
	val percent = ((fps / baseline) * 100f).coerceIn(0f, 100f)

	return DecoderActivityMetric(
		fps = fps,
		percent = percent,
	)
}

private fun List<Float?>.appendUsageSample(value: Float?, sampleCount: Int = UsageGraphSampleCount) =
	(this + value).takeLast(sampleCount)

private fun Float?.formatUsagePercent() = this
	?.let { value -> "${value.toInt()}%" }
	?: "n/a"

private fun UsageMetric.formatUsageValue(): String = percent.formatUsagePercent()

private fun Float.formatTemperature() = "%.0fC".format(this)

private fun Float?.formatThroughput(): String = this
	?.let { bytesPerSecond ->
		val bitsPerSecond = bytesPerSecond * 8f
		when {
			bitsPerSecond >= 1_000_000f -> "%.1f Mbps".format(bitsPerSecond / 1_000_000f)
			bitsPerSecond >= 1_000f -> "%.0f Kbps".format(bitsPerSecond / 1_000f)
			else -> "%.0f bps".format(bitsPerSecond)
		}
	}
	?: "n/a"

private fun ByteUsageMetric.totalBytesPerSecond(): Float? =
	if (readBytesPerSecond != null && writeBytesPerSecond != null) readBytesPerSecond + writeBytesPerSecond else null

private fun Float?.formatIoRate(): String = this?.let {
	if (it >= MIB_BYTES) "%.1f MiB/s".format(it / MIB_BYTES) else "%.0f KiB/s".format(it / KIB_BYTES)
} ?: "n/a"

private fun Long?.formatDataAmount(): String = this?.let {
	when {
		it >= GIB_BYTES -> "%.1f GiB".format(it / GIB_BYTES)
		it >= MIB_BYTES -> "%.1f MiB".format(it / MIB_BYTES)
		else -> "%.0f KiB".format(it / KIB_BYTES)
	}
} ?: "n/a"

private fun MemoryUsageMetric.formatMemoryUsage() = when {
	percent != null && usedBytes != null && totalBytes != null ->
		"${percent.toInt()}% ${usedBytes.formatRamAmount()}/${totalBytes.formatRamAmount()}"

	else -> "n/a"
}

private fun Long.formatRamAmount(): String {
	val gib = 1_073_741_824.0
	val mib = 1_048_576.0
	return when {
		this >= 1_073_741_824L -> "%.1fG".format(this / gib)
		else -> "%.0fM".format(this / mib)
	}
}

private fun DecoderActivityMetric.formatDecoderActivity() = when {
	percent != null && fps != null -> "${percent.toInt()}% ${fps.formatFps()}fps"
	fps != null -> "${fps.formatFps()}fps"
	else -> "n/a"
}

private fun RenderFrameMetric.formatRenderFrameMetric() = when {
	percent != null && durationMs != null -> "${percent.toInt()}% ${durationMs.formatRenderMs()}ms"
	durationMs != null -> "${durationMs.formatRenderMs()}ms"
	else -> "n/a"
}

private fun Float.formatFps() = "%.3f".format(this)

private fun Float.formatRenderMs() = when {
	this >= 10f -> "%.0f".format(this)
	else -> "%.1f".format(this)
}

private tailrec fun Context.findPlaybackActivity(): Activity? = when (this) {
	is Activity -> this
	is ContextWrapper -> baseContext.findPlaybackActivity()
	else -> null
}

private data class DecoderFrameSnapshot(
	val decodedFrames: Int,
	val elapsedRealtimeMs: Long,
)

private data class DecoderActivityMetric(
	val fps: Float? = null,
	val percent: Float? = null,
)
