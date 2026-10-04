package org.jellyfin.playback.jellyfin.mediastream

import java.net.URI
import java.net.URLDecoder

internal fun String.withDtsEncodingBitrateFloor(): String {
	val uri = runCatching { URI(this) }.getOrNull() ?: return this
	val query = uri.rawQuery ?: return this
	val parts = query.split('&').toMutableList()
	val options = runCatching {
		parts.map {
			URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() to
				URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
		}
	}.getOrNull() ?: return this
	if (options.map { it.first }.distinct().size != options.size) return this
	if (options.firstOrNull { it.first == "audiocodec" }?.second?.lowercase() !in setOf("dts", "dca")) return this
	val bitrateIndex = options.indexOfFirst { it.first == "audiobitrate" }
	if (bitrateIndex >= 0 && (options[bitrateIndex].second.toIntOrNull() ?: 0) >= 768_000) return this
	// A low source bitrate (e.g. EAC3 at 640 kb/s) cannot encode DTS 5.1 at 48 kHz.
	// Raise only the DTS encoding request; the server ignores this for audio copy.
	if (bitrateIndex >= 0) {
		parts[bitrateIndex] = parts[bitrateIndex].substringBefore('=') + "=768000"
	} else {
		parts += "AudioBitrate=768000"
	}
	return substringBefore('?') + "?" + parts.joinToString("&") +
		(uri.rawFragment?.let { "#$it" } ?: "")
}
