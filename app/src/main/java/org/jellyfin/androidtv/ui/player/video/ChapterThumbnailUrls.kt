package org.jellyfin.androidtv.ui.player.video

import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.sdk.buildChapterItems
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
internal fun BaseItemDto.getChapterThumbnailUrls(
	api: ApiClient,
	fillWidth: Int,
	fillHeight: Int,
): List<String> = buildChapterItems()
	.mapNotNull { chapter -> chapter.image }
	.map { image ->
		image.getUrl(
			api = api,
			fillWidth = fillWidth,
			fillHeight = fillHeight,
		)
	}
	.distinct()
