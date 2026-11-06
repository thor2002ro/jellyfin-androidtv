package org.jellyfin.playback.mpv

import java.util.concurrent.Executor

/** Called on the main thread; native work and disposal use one serial executor. */
internal class LibMPVPlayerLoader<T>(
	private val worker: Executor,
	private val reply: (() -> Unit) -> Unit,
	private val dispose: (T) -> Unit,
) {
	private var generation = 0L
	private var closed = false

	fun load(create: () -> T, ready: (T) -> Unit, failed: (Throwable) -> Unit) {
		if (closed) return
		val request = ++generation
		worker.execute {
			val result = runCatching(create)
			reply {
				if (closed || request != generation) {
					result.getOrNull()?.let(::retire)
				} else {
					result.fold(ready, failed)
				}
			}
		}
	}

	fun execute(work: () -> Unit) = worker.execute(work)

	fun retire(player: T) = execute { dispose(player) }

	fun close() {
		closed = true
		generation++
	}
}
