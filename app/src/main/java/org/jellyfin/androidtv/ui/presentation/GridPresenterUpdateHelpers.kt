package org.jellyfin.androidtv.ui.presentation

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

internal fun Modifier.restoreFocusRequesterWhen(focused: Boolean, requester: FocusRequester): Modifier =
	if (focused) focusRequester(requester) else this

internal class GridSelectionNotificationTracker {
	private var position = -1
	private var item: Any? = null

	fun shouldNotify(position: Int, item: Any): Boolean {
		if (this.position == position && this.item === item) return false
		this.position = position
		this.item = item
		return true
	}

	fun reset() {
		position = -1
		item = null
	}
}

internal class GridAdapterSyncGate {
	private var pending = false
	private var generation = 0

	fun request(
		post: (action: () -> Unit) -> Unit,
		sync: () -> Unit,
	) {
		if (pending) return
		pending = true
		val scheduledGeneration = ++generation
		post action@{
			if (scheduledGeneration != generation) return@action
			pending = false
			sync()
		}
	}

	fun cancel() {
		generation++
		pending = false
	}
}
