package org.jellyfin.androidtv.ui.player.video

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi

internal interface PlaybackRenderFrameSampler {
	fun start()
	fun stop()
	fun sample(): RenderFrameMetric
}

internal fun createPlaybackRenderFrameSampler(window: Window): PlaybackRenderFrameSampler? =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) PlaybackRenderFrameSamplerApi24(window)
	else null

@RequiresApi(Build.VERSION_CODES.N)
private class PlaybackRenderFrameSamplerApi24(
	private val window: Window,
) : PlaybackRenderFrameSampler {
	private val lock = Any()
	private var handlerThread: HandlerThread? = null
	private var listener: Window.OnFrameMetricsAvailableListener? = null
	private var renderDurationNs = 0L
	private var renderFrameCount = 0
	private var gpuDurationNs = 0L
	private var gpuFrameCount = 0

	override fun start() {
		if (listener != null) return

		val thread = HandlerThread("PlaybackRenderFrameSampler").also { it.start() }
		val frameListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
			val gpuDuration = frameMetrics.readGpuDuration()
			val renderDuration = frameMetrics.readRenderDuration()

			synchronized(lock) {
				if (gpuDuration > 0L) {
					gpuDurationNs += gpuDuration
					gpuFrameCount++
				}
				if (renderDuration > 0L) {
					renderDurationNs += renderDuration
					renderFrameCount++
				}
			}
		}

		runCatching {
			window.addOnFrameMetricsAvailableListener(frameListener, Handler(thread.looper))
		}.getOrElse {
			thread.quitSafely()
			return
		}
		handlerThread = thread
		listener = frameListener
	}

	override fun stop() {
		listener?.let { frameListener ->
			runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) }
		}
		listener = null
		handlerThread?.quitSafely()
		handlerThread = null

		synchronized(lock) {
			renderDurationNs = 0L
			renderFrameCount = 0
			gpuDurationNs = 0L
			gpuFrameCount = 0
		}
	}

	override fun sample(): RenderFrameMetric {
		val metric = synchronized(lock) {
			val averageGpuDurationMs = if (gpuFrameCount > 0) {
				gpuDurationNs.toFloat() / gpuFrameCount.toFloat() / NanosecondsPerMillisecond
			} else {
				null
			}
			val averageRenderDurationMs = if (renderFrameCount > 0) {
				renderDurationNs.toFloat() / renderFrameCount.toFloat() / NanosecondsPerMillisecond
			} else {
				null
			}

			renderDurationNs = 0L
			renderFrameCount = 0
			gpuDurationNs = 0L
			gpuFrameCount = 0

			when {
				averageGpuDurationMs != null -> RenderFrameMetric(
					label = "UI GPU",
					durationMs = averageGpuDurationMs,
					percent = averageGpuDurationMs.toFrameBudgetPercent(),
				)

				averageRenderDurationMs != null -> RenderFrameMetric(
					label = "Render",
					durationMs = averageRenderDurationMs,
					percent = averageRenderDurationMs.toFrameBudgetPercent(),
				)

				else -> RenderFrameMetric()
			}
		}

		return metric
	}

	private fun Float.toFrameBudgetPercent() =
		(this / FrameBudgetMs * 100f).coerceIn(0f, 100f)

	@Suppress("NewApi")
	private fun FrameMetrics.readRenderDuration(): Long {
		val commandIssueDuration = getMetric(FrameMetrics.COMMAND_ISSUE_DURATION).coerceAtLeast(0L)
		val swapBuffersDuration = getMetric(FrameMetrics.SWAP_BUFFERS_DURATION).coerceAtLeast(0L)
		val renderDuration = commandIssueDuration + swapBuffersDuration
		if (renderDuration > 0L) return renderDuration

		return getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0L)
	}

	@Suppress("NewApi")
	private fun FrameMetrics.readGpuDuration(): Long {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0L

		return getMetric(FrameMetrics.GPU_DURATION).coerceAtLeast(0L)
	}

	companion object {
		private const val NanosecondsPerMillisecond = 1_000_000f
		private const val FrameBudgetMs = 16.6667f
	}
}

internal data class RenderFrameMetric(
	val label: String = "Render",
	val durationMs: Float? = null,
	val percent: Float? = null,
)
