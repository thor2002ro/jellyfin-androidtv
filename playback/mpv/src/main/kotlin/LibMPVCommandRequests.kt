package org.jellyfin.playback.mpv

internal class LibMPVCommandRequests {
	private var nextRequestId = 0L
	private val latest = mutableMapOf<String, Long>()

	fun nextId(): Long = ++nextRequestId

	fun accepted(command: String, requestId: Long) {
		if (command == "loadfile" || command == "stop") latest.clear()
		latest[command] = requestId
	}

	fun isCurrent(command: String?, requestId: Long?): Boolean =
		requestId != null && latest[command] == requestId
}
