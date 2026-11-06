package org.jellyfin.androidtv.util

internal class MemoryLogBuffer(maxBytes: Int = 10 * 1024 * 1024) {
	private val bytes = ByteArray(maxBytes.also { require(it > 0) })
	private var end = 0
	private var size = 0

	@Synchronized
	fun append(message: String) {
		val encoded = message.toByteArray(Charsets.UTF_8)
		val count = minOf(encoded.size, bytes.size)
		val offset = encoded.size - count
		val first = minOf(count, bytes.size - end)
		encoded.copyInto(bytes, end, offset, offset + first)
		encoded.copyInto(bytes, 0, offset + first, offset + count)
		end = (end + count) % bytes.size
		size = minOf(bytes.size, size + count)
	}

	fun dump(): String {
		val snapshot = synchronized(this) {
			val start = (end - size + bytes.size) % bytes.size
			val first = minOf(size, bytes.size - start)
			ByteArray(size).also {
				bytes.copyInto(it, 0, start, start + first)
				bytes.copyInto(it, first, 0, size - first)
			}
		}
		// Rotation can leave the oldest UTF-8 character partially overwritten.
		var start = 0
		while (start < snapshot.size && snapshot[start].toInt() and 0xC0 == 0x80) start++
		return snapshot.decodeToString(startIndex = start)
	}
}
