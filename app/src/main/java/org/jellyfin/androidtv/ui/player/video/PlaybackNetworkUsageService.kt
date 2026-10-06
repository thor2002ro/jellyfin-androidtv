package org.jellyfin.androidtv.ui.player.video

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.playback.core.plugin.PlayerService

internal class PlaybackNetworkUsageService(
	private val context: Context,
	private val readSnapshot: (() -> ByteCounterSnapshot?)? = null,
) : PlayerService() {
	private val latest = MutableStateFlow(NetworkThroughputMetric())
	val samples = latest.asStateFlow()
	private val appUid by lazy { context.applicationInfo.uid }
	private var useDeviceCounters = false
	private var countersAvailable = true

	override suspend fun onInitialize() = withContext(Dispatchers.IO) {
		val tracker = ByteUsageTracker()
		try {
			while (isActive) {
				val snapshot = if (readSnapshot != null) readSnapshot.invoke() else readNetworkSnapshot()
				val usage = tracker.sample(snapshot)
				latest.value = NetworkThroughputMetric(
					downloadLabel = if (usage.source == "Device") "Device down" else "Down",
					uploadLabel = if (usage.source == "Device") "Device up" else "Up",
					downloadBytesPerSecond = usage.readBytesPerSecond,
					uploadBytesPerSecond = usage.writeBytesPerSecond,
					dataUsage = usage,
				)
				delay(1_000L)
			}
		} finally {
			latest.value = NetworkThroughputMetric()
		}
	}

	private fun readNetworkSnapshot(): ByteCounterSnapshot? {
		if (!countersAvailable) return null
		return runCatching {
			var received = if (useDeviceCounters) TrafficStats.getTotalRxBytes() else TrafficStats.getUidRxBytes(appUid)
			var sent = if (useDeviceCounters) TrafficStats.getTotalTxBytes() else TrafficStats.getUidTxBytes(appUid)
			if (!useDeviceCounters && (received < 0L || sent < 0L)) {
				useDeviceCounters = true
				received = TrafficStats.getTotalRxBytes()
				sent = TrafficStats.getTotalTxBytes()
			}
			if (received < 0L || sent < 0L) {
				countersAvailable = false
				null
			} else ByteCounterSnapshot(if (useDeviceCounters) "Device" else "App", received, sent, SystemClock.elapsedRealtime())
		}.getOrElse {
			countersAvailable = false
			null
		}
	}
}
