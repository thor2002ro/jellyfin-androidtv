package org.jellyfin.playback.jellyfin.livetv

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

fun BaseItemDto.liveTvChannelId() = when (type) {
	BaseItemKind.TV_CHANNEL,
	BaseItemKind.LIVE_TV_CHANNEL -> id
	BaseItemKind.PROGRAM,
	BaseItemKind.TV_PROGRAM,
	BaseItemKind.LIVE_TV_PROGRAM -> parentId ?: channelId

	else -> null
}
