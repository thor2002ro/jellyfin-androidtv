package org.jellyfin.playback.mpv

import android.os.Build
import `is`.xyz.mpv.MPV
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import java.util.Locale
import kotlin.time.Duration

internal enum class LibMPVShieldFallback(val metricValue: String) {
	HI10P("Hi10P software decode"),
	MPEG2("MPEG-2 software decode"),
}

internal enum class LibMPVNvidiaFallbackConfigurationChange {
	NONE,
	PRESERVE,
	REMOVE,
}

internal fun libMPVNvidiaFallbackConfigurationChange(
	activeFallback: LibMPVShieldFallback?,
	fallbackAllowed: Boolean,
): LibMPVNvidiaFallbackConfigurationChange = when {
	activeFallback == null -> LibMPVNvidiaFallbackConfigurationChange.NONE
	fallbackAllowed -> LibMPVNvidiaFallbackConfigurationChange.PRESERVE
	else -> LibMPVNvidiaFallbackConfigurationChange.REMOVE
}

internal fun libMPVNvidiaFallbackRemovalNeedsResync(restoredDecoderValue: String): Boolean =
	!restoredDecoderValue.equals("no", ignoreCase = true)

internal data class LibMPVNvidiaFallbackResync(
	val position: Duration?,
	val resumeAfter: Boolean,
)

internal class LibMPVNvidiaFallbackResyncState {
	private var pending: LibMPVNvidiaFallbackResync? = null
	val isPending: Boolean get() = pending != null

	fun schedule(position: Duration?, resumeAfter: Boolean) {
		pending = LibMPVNvidiaFallbackResync(position, resumeAfter)
	}

	fun updateResumeAfter(resumeAfter: Boolean): Boolean {
		val request = pending ?: return false
		pending = request.copy(resumeAfter = resumeAfter)
		return true
	}

	fun consume(): LibMPVNvidiaFallbackResync? = pending.also { pending = null }
}

private val LIBMPV_NVIDIA_SOFTWARE_OPTIONS: Map<String, String> = linkedMapOf(
	"hwdec" to "no",
	"vo" to "gpu-next",
)

private val LIBMPV_NVIDIA_HI10P_OPTIONS: Map<String, String> = LIBMPV_NVIDIA_SOFTWARE_OPTIONS + linkedMapOf(
	"vd-lavc-skiploopfilter" to "nonref",
)

internal fun libMPVNvidiaFallbackOptions(fallback: LibMPVShieldFallback): Map<String, String> =
	when (fallback) {
		LibMPVShieldFallback.HI10P -> LIBMPV_NVIDIA_HI10P_OPTIONS
		LibMPVShieldFallback.MPEG2 -> LIBMPV_NVIDIA_SOFTWARE_OPTIONS
	}

internal fun isLibMPVNvidiaDevice(
	manufacturer: String = Build.MANUFACTURER,
): Boolean = manufacturer.contains("NVIDIA", ignoreCase = true)

internal fun selectLibMPVShieldFallback(
	isNvidiaDevice: Boolean,
	fallbackAllowed: Boolean,
	codec: String?,
	profile: String?,
	pixelFormat: String?,
	bitDepth: Int? = null,
): LibMPVShieldFallback? {
	if (!isNvidiaDevice || !fallbackAllowed) return null

	val normalizedCodec = codec.orEmpty().trim().lowercase(Locale.US)
	if (normalizedCodec in setOf("mpeg2", "mpeg2video")) return LibMPVShieldFallback.MPEG2
	if (normalizedCodec !in setOf("avc", "avc1", "h264")) return null

	val normalizedProfile = profile.orEmpty().trim().lowercase(Locale.US)
	val normalizedPixelFormat = pixelFormat.orEmpty().trim().lowercase(Locale.US)
	return LibMPVShieldFallback.HI10P.takeIf {
		bitDepth == 10 ||
			normalizedProfile.contains("10") ||
			normalizedProfile.contains("hi10") ||
			normalizedPixelFormat.contains("p10") ||
			normalizedPixelFormat.contains("10le")
	}
}

internal fun PlayableMediaStream.selectLibMPVShieldFallback(
	isNvidiaDevice: Boolean,
	fallbackAllowed: Boolean,
): LibMPVShieldFallback? {
	if (conversionMethod == MediaConversionMethod.Transcode) return null
	val video = tracks.filterIsInstance<MediaStreamVideoTrack>().firstOrNull() ?: return null
	return selectLibMPVShieldFallback(
		isNvidiaDevice = isNvidiaDevice,
		fallbackAllowed = fallbackAllowed,
		codec = video.codec,
		profile = video.profile,
		pixelFormat = null,
		bitDepth = video.bitDepth,
	)
}

internal fun MPV.selectLibMPVShieldFallback(
	isNvidiaDevice: Boolean,
	fallbackAllowed: Boolean,
): LibMPVShieldFallback? {
	if (!isNvidiaDevice || !fallbackAllowed) return null
	val selectedVideoTrack = runCatching {
		getPropertyNode("track-list")
			?.asArray()
			?.firstOrNull { node ->
				val track = node.asMap()
				track?.get("type")?.asString() == "video" && track["selected"]?.asBoolean() == true
			}
			?.asMap()
	}.getOrNull()
	return selectLibMPVShieldFallback(
		isNvidiaDevice = true,
		fallbackAllowed = true,
		codec = selectedVideoTrack?.get("codec")?.asString()
			?: runCatching { getPropertyString("current-tracks/video/codec") }.getOrNull(),
		profile = selectedVideoTrack?.get("codec-profile")?.asString()
			?: runCatching { getPropertyString("current-tracks/video/codec-profile") }.getOrNull(),
		pixelFormat = runCatching { getPropertyString("video-params/pixelformat") }.getOrNull(),
	)
}
