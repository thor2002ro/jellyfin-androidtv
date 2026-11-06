package org.jellyfin.playback.mpv

import java.util.concurrent.ConcurrentHashMap

// Readers never enter libmpv: a stalled native player must not block the UI.
internal class LibMPVObservedProperties {
	private val values = ConcurrentHashMap<String, String>()

	fun update(name: String, value: String?) {
		if (value == null) values.remove(name) else values[name] = value
	}

	fun string(name: String): String? = values[name]
	fun integer(name: String): Int? = values[name]?.toIntOrNull()
	fun double(name: String): Double? = values[name]?.toDoubleOrNull()?.takeIf(Double::isFinite)
	fun clear() = values.clear()
}

internal val libMPVObservedStatProperties = setOf(
	"audio-bitrate",
	"audio-out-params/format",
	"audio-params/channels",
	"audio-params/format",
	"audio-params/samplerate",
	"audio-speed-correction",
	"avsync",
	"cache-speed",
	"container-fps",
	"current-ao",
	"current-gpu-context",
	"current-tracks/audio/codec",
	"current-tracks/audio/decoder",
	"current-tracks/audio/decoder-desc",
	"current-tracks/sub/codec",
	"current-tracks/video/codec",
	"current-tracks/video/decoder",
	"current-tracks/video/decoder-desc",
	"current-tracks/video/dolby-vision-profile",
	"current-vo",
	"decoder-frame-drop-count",
	"deinterlace-active",
	"demuxer-cache-idle",
	"display-fps",
	"display-sync-active",
	"estimated-display-fps",
	"estimated-vf-fps",
	"frame-drop-count",
	"mistimed-frame-count",
	"total-avsync-change",
	"video-bitrate",
	"video-frame-info/picture-type",
	"video-frame-info/repeat",
	"video-frame-info/tff",
	"video-params/colorlevels",
	"video-params/colormatrix",
	"video-params/gamma",
	"video-params/max-cll",
	"video-params/max-fall",
	"video-params/max-luma",
	"video-params/pixelformat",
	"video-params/primaries",
	"video-params/scene-max-b",
	"video-params/scene-max-g",
	"video-params/scene-max-r",
	"video-speed-correction",
	"vo-delayed-frame-count",
	"vsync-jitter",
	"vsync-ratio",
	"time-pos",
	"duration",
	"demuxer-cache-time",
	"demuxer-cache-duration",
	"seekable",
)
