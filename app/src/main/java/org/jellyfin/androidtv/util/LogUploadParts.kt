package org.jellyfin.androidtv.util

internal fun logUploadParts(logs: String): List<String> {
	val bytes = logs.toByteArray(Charsets.UTF_8)
	val parts = mutableListOf<String>()
	var start = 0
	while (start < bytes.size) {
		var end = minOf(start + 999_900, bytes.size)
		while (end < bytes.size && bytes[end].toInt() and 0xC0 == 0x80) end--
		parts.add(bytes.decodeToString(start, end))
		start = end
	}
	return parts
}
