package org.jellyfin.androidtv.util.apiclient

import coil3.network.NetworkHeaders
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

data class TrickplayTileSheet(
	val url: String,
	val headers: NetworkHeaders,
)

data class TrickplayImage(
	val url: String,
	val headers: NetworkHeaders,
	val offsetX: Int,
	val offsetY: Int,
	val width: Int,
	val height: Int,
)

fun BaseItemDto.getTrickplayImage(
	api: ApiClient,
	mediaSourceId: String?,
	timeMs: Long,
): TrickplayImage? {
	val (sourceId, info) = getTrickplaySource(mediaSourceId) ?: return null
	val sourceUuid = sourceId?.toUUIDOrNull() ?: return null

	val tile = timeMs.coerceAtLeast(0).floorDiv(info.interval).toInt()
	val tilesPerSheet = info.tileWidth * info.tileHeight
	val sheetIndex = tile / tilesPerSheet
	val tileOffset = tile % tilesPerSheet
	val tileOffsetX = tileOffset % info.tileWidth
	val tileOffsetY = tileOffset / info.tileWidth

	return TrickplayImage(
		url = api.trickplayApi.getTrickplayTileImageUrl(
			itemId = id,
			width = info.width,
			index = sheetIndex,
			mediaSourceId = sourceUuid,
		),
		headers = api.trickplayHeaders(),
		offsetX = tileOffsetX * info.width,
		offsetY = tileOffsetY * info.height,
		width = info.width,
		height = info.height,
	)
}

fun BaseItemDto.getTrickplayTileSheets(
	api: ApiClient,
	mediaSourceId: String?,
): List<TrickplayTileSheet> {
	val (sourceId, info) = getTrickplaySource(mediaSourceId) ?: return emptyList()
	val sourceUuid = sourceId?.toUUIDOrNull() ?: return emptyList()
	val tilesPerSheet = info.tileWidth * info.tileHeight
	val sheetCount = (info.thumbnailCount + tilesPerSheet - 1) / tilesPerSheet
	if (sheetCount <= 0) return emptyList()

	val headers = api.trickplayHeaders()
	return (0 until sheetCount).map { sheetIndex ->
		TrickplayTileSheet(
			url = api.trickplayApi.getTrickplayTileImageUrl(
				itemId = id,
				width = info.width,
				index = sheetIndex,
				mediaSourceId = sourceUuid,
			),
			headers = headers,
		)
	}
}

private fun BaseItemDto.getTrickplaySource(mediaSourceId: String?): Pair<String?, TrickplayInfoDto>? {
	val trickplayBySource = trickplay ?: return null
	val sourceId = if (mediaSourceId != null) {
		mediaSourceId.takeIf(trickplayBySource::containsKey)
	} else {
		mediaSources?.firstNotNullOfOrNull { mediaSource -> mediaSource.id?.takeIf(trickplayBySource::containsKey) }
			?: trickplayBySource.keys.firstOrNull()
	}
	val info = trickplayBySource[sourceId]?.values?.firstOrNull() ?: return null
	if (
		info.interval <= 0 ||
		info.tileWidth <= 0 ||
		info.tileHeight <= 0 ||
		info.width <= 0 ||
		info.height <= 0
	) return null

	return sourceId to info
}

private fun ApiClient.trickplayHeaders() = NetworkHeaders.Builder().apply {
	set(
		key = "Authorization",
		value = AuthorizationHeaderBuilder.buildHeader(
			clientInfo.name,
			clientInfo.version,
			deviceInfo.id,
			deviceInfo.name,
			accessToken
		)
	)
}.build()
