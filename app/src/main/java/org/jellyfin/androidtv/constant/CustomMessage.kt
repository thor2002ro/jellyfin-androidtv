package org.jellyfin.androidtv.constant

sealed interface CustomMessage {
	data object RefreshCurrentItem : CustomMessage
	data object RefreshHomeConfiguration : CustomMessage
	data object ActionComplete : CustomMessage
}
