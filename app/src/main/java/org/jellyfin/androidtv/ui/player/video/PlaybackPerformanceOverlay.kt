package org.jellyfin.androidtv.ui.player.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.model.PlaybackFrameStats

private const val UsageGraphSampleCount = 30
private const val DecoderGraphFallbackFrameRate = 60f

@Composable
internal fun PlaybackPerformanceOverlay(
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
			)
			renderFrameMetric = renderFrameSampler?.sample() ?: RenderFrameMetric()
			previousDecoderSnapshot = currentDecoderSnapshot
			decoderUsageHistory = decoderUsageHistory.appendUsageSample(decoderActivity.percent)
			renderUsageHistory = renderUsageHistory.appendUsageSample(renderFrameMetric.percent)

			val sample = withContext(Dispatchers.IO) { performanceSampler.sample() }
			performanceSample = sample
			cpuUsageHistory = cpuUsageHistory.appendUsageSample(sample.cpu.percent)
			gpuUsageHistory = gpuUsageHistory.appendUsageSample(sample.gpu.percent)
			ramUsageHistory = ramUsageHistory.appendUsageSample(sample.memory.percent)
			networkDownloadHistory = networkDownloadHistory.appendUsageSample(sample.network.downloadBytesPerSecond)
			networkUploadHistory = networkUploadHistory.appendUsageSample(sample.network.uploadBytesPerSecond)
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
	decoderUsageHistory: List<Float?>,
	renderUsageHistory: List<Float?>,
) {
	Column(
		modifier = Modifier.width(146.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			PlaybackTemperatureOverlay(
				label = "CPU",
				temperatureCelsius = sample.cpu.temperatureCelsius,
				modifier = Modifier.weight(1f),
			)
			PlaybackTemperatureOverlay(
				label = "GPU",
				temperatureCelsius = sample.gpu.temperatureCelsius,
				modifier = Modifier.weight(1f),
			)
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
	temperatureCelsius: Float?,
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
			text = temperatureCelsius.formatTemperatureValue(),
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
			color = Color(0xFFAA5CC3),
		)
		PlaybackUsageGraph(
			metric = UsageMetric(label = "Decoder", percent = decoderActivity.percent),
			history = decoderUsageHistory,
			color = Color(0xFF55D98B),
			valueText = decoderActivity.formatDecoderActivity(),
		)
		PlaybackUsageGraph(
			metric = UsageMetric(label = renderFrameMetric.label, percent = renderFrameMetric.percent),
			history = renderUsageHistory,
			color = Color(0xFF4DB6AC),
			valueText = renderFrameMetric.formatRenderFrameMetric(),
		)
		PlaybackUsageGraph(
			metric = sample.cpu,
			history = cpuUsageHistory,
			color = Color(0xFF00A4DC),
		)
		PlaybackUsageGraph(
			metric = UsageMetric(label = sample.memory.label, percent = sample.memory.percent),
			history = ramUsageHistory,
			color = Color(0xFFFFB74D),
			valueText = sample.memory.formatMemoryUsage(),
		)
		PlaybackNetworkGraph(
			network = sample.network,
			downloadHistory = networkDownloadHistory,
			uploadHistory = networkUploadHistory,
		)
	}
}

@Composable
private fun PlaybackNetworkGraph(
	network: NetworkThroughputMetric,
	downloadHistory: List<Float?>,
	uploadHistory: List<Float?>,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		PlaybackThroughputGraph(
			label = network.downloadLabel,
			bytesPerSecond = network.downloadBytesPerSecond,
			history = downloadHistory,
			color = Color(0xFF64B5F6),
			modifier = Modifier.weight(1f),
		)
		PlaybackThroughputGraph(
			label = network.uploadLabel,
			bytesPerSecond = network.uploadBytesPerSecond,
			history = uploadHistory,
			color = Color(0xFFFFD54F),
			modifier = Modifier.weight(1f),
		)
	}
}

@Composable
private fun PlaybackThroughputGraph(
	label: String,
	bytesPerSecond: Float?,
	history: List<Float?>,
	color: Color,
	modifier: Modifier = Modifier,
) {
	val maxBytesPerSecond = history
		.filterNotNull()
		.maxOrNull()
		?.coerceAtLeast(1f)
	val normalizedHistory = history.map { value ->
		when {
			value == null || maxBytesPerSecond == null -> null
			else -> (value / maxBytesPerSecond * 100f).coerceIn(0f, 100f)
		}
	}

	PlaybackUsageGraph(
		metric = UsageMetric(label = label, percent = null),
		history = normalizedHistory,
		color = color,
		valueText = bytesPerSecond.formatThroughput(),
		modifier = modifier,
	)
}

@Composable
private fun PlaybackUsageGraph(
	metric: UsageMetric,
	history: List<Float?>,
	color: Color,
	modifier: Modifier = Modifier,
	valueText: String = metric.formatUsageValue(),
) {
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(1.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			PlaybackInfoGraphText(
				text = metric.label,
				modifier = Modifier.weight(1.15f),
				minLines = 2,
			)
			PlaybackInfoGraphText(
				text = valueText,
				modifier = Modifier.weight(1.45f),
				textAlign = TextAlign.End,
				minLines = 2,
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

			val samples = history.takeLast(UsageGraphSampleCount)
			if (samples.isEmpty()) return@Canvas

			val slotWidth = size.width / UsageGraphSampleCount
			val barWidth = maxOf(1f, slotWidth - 1.dp.toPx())
			val startIndex = UsageGraphSampleCount - samples.size
			samples.forEachIndexed { index, percent ->
				val value = percent?.coerceIn(0f, 100f) ?: return@forEachIndexed
				val barHeight = maxOf(1.dp.toPx(), size.height * (value / 100f))
				val x = (startIndex + index) * slotWidth
				drawRoundRect(
					color = color.copy(alpha = 0.7f),
					topLeft = Offset(x = x, y = size.height - barHeight),
					size = Size(width = barWidth, height = barHeight),
					cornerRadius = cornerRadius,
				)
			}
		}
	}
}

@Composable
private fun PlaybackInfoGraphText(
	text: String,
	modifier: Modifier = Modifier,
	textAlign: TextAlign = TextAlign.Start,
	minLines: Int = 1,
) {
	BoxWithConstraints(modifier = modifier) {
		var fontSize by remember(text, maxWidth) { mutableStateOf(6.sp) }

		Text(
			text = text,
			modifier = Modifier.fillMaxWidth(),
			textAlign = textAlign,
			softWrap = true,
			minLines = minLines,
			maxLines = 2,
			onTextLayout = { result ->
				if (result.didOverflowWidth && fontSize.value > 4.5f) {
					fontSize = (fontSize.value - 0.5f).sp
				}
			},
			style = TextStyle(
				color = Color.White,
				fontSize = fontSize,
				lineHeight = (fontSize.value + 0.5f).sp,
				fontFamily = FontFamily.Monospace,
				fontWeight = FontWeight.W700,
			),
		)
	}
}

private fun calculateDecoderActivity(
	previous: DecoderFrameSnapshot?,
	current: DecoderFrameSnapshot,
	expectedFrameRate: Float?,
): DecoderActivityMetric {
	if (previous == null) return DecoderActivityMetric()

	val frameDelta = current.decodedFrames - previous.decodedFrames
	val elapsedDeltaMs = current.elapsedRealtimeMs - previous.elapsedRealtimeMs
	if (frameDelta < 0 || elapsedDeltaMs <= 0) return DecoderActivityMetric()

	val fps = frameDelta.toFloat() * 1_000f / elapsedDeltaMs.toFloat()
	val baseline = expectedFrameRate ?: DecoderGraphFallbackFrameRate
	val percent = ((fps / baseline) * 100f).coerceIn(0f, 100f)

	return DecoderActivityMetric(
		fps = fps,
		percent = percent,
	)
}

private fun List<Float?>.appendUsageSample(value: Float?) =
	(this + value).takeLast(UsageGraphSampleCount)

private fun Float?.formatUsagePercent() = this
	?.let { value -> "${value.toInt()}%" }
	?: "n/a"

private fun UsageMetric.formatUsageValue(): String = percent.formatUsagePercent()

private fun Float.formatTemperature() = "%.0fC".format(this)

private fun Float?.formatTemperatureValue() = this?.formatTemperature() ?: "n/a"

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

private fun Float.formatFps() = when {
	this >= 10f -> "%.0f".format(this)
	else -> "%.1f".format(this)
}

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
