package org.jellyfin.updater

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppUpdateCheckState(
	val checking: Boolean = false,
	val result: UpdateCheckResult? = null,
)

internal class AppUpdateCheck(
	private val scope: CoroutineScope,
	private val fetch: suspend (Boolean) -> UpdateCheckResult,
) {
	private val current = MutableStateFlow(AppUpdateCheckState())
	val state = current.asStateFlow()
	private val mutex = Mutex()
	private var inFlight: Deferred<UpdateCheckResult>? = null

	suspend fun run(force: Boolean): UpdateCheckResult {
		val pending = mutex.withLock {
			inFlight ?: run {
				current.value = AppUpdateCheckState(checking = true)
				scope.async {
					var result: UpdateCheckResult? = null
					try {
						fetch(force).also { result = it }
					} finally {
						mutex.withLock {
							inFlight = null
							current.value = AppUpdateCheckState(result = result)
						}
					}
				}.also { inFlight = it }
			}
		}
		val result = pending.await()
		return if (force && result == UpdateCheckResult.Skipped) run(force = true) else result
	}

	fun reset() {
		if (!mutex.tryLock()) return
		try {
			if (inFlight == null) current.value = AppUpdateCheckState()
		} finally {
			mutex.unlock()
		}
	}
}
