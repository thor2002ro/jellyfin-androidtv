package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jellyfin.androidtv.constant.CustomMessage

interface CustomMessageRepository {
	val message: SharedFlow<CustomMessage?>
	fun pushMessage(message: CustomMessage)
}

class CustomMessageRepositoryImpl : CustomMessageRepository {
	private val _message = MutableSharedFlow<CustomMessage?>(
		replay = 1,
		extraBufferCapacity = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	).apply { tryEmit(null) }
	override val message get() = _message.asSharedFlow()

	override fun pushMessage(message: CustomMessage) {
		_message.tryEmit(message)
	}
}
