package org.jellyfin.androidtv.util.apiclient

import coil3.network.NetworkHeaders
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickPlayApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import kotlin.math.sqrt

const val TRICKPLAY_THUMBNAIL_PIXEL_WIDTH = 320
const val TRICKPLAY_THUMBNAIL_PIXEL_HEIGHT = 180
const val TRICKPLAY_SHEET_MAX_BYTES = 30 * 1024 * 1024
const val TRICKPLAY_DECODE_MAX_BYTES_PER_PIXEL = 4
private const val TRICKPLAY_SHEET_MAX_PIXELS = TRICKPLAY_SHEET_MAX_BYTES / TRICKPLAY_DECODE_MAX_BYTES_PER_PIXEL

data class TrickplayTileSheet(
	val url: String,
	val headers: NetworkHeaders,
	val columns: Int,
	val rows: Int,
) {
	init {
		require(columns > 0 && rows > 0)
		require(columns.toLong() * rows <= TRICKPLAY_SHEET_MAX_PIXELS)
	}

	private val decodeScale get() = minOf(
		1.0,
		sqrt(
			TRICKPLAY_SHEET_MAX_PIXELS.toDouble() /
				(TRICKPLAY_THUMBNAIL_PIXEL_WIDTH.toDouble() * TRICKPLAY_THUMBNAIL_PIXEL_HEIGHT * columns * rows)
		)
	)
	val decodeWidth get() = (TRICKPLAY_THUMBNAIL_PIXEL_WIDTH * decodeScale).toInt().coerceAtLeast(1) * columns
	val decodeHeight get() = (TRICKPLAY_THUMBNAIL_PIXEL_HEIGHT * decodeScale).toInt().coerceAtLeast(1) * rows
}

data class TrickplayImage(
	val sheet: TrickplayTileSheet,
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

	val tile = timeMs.coerceAtLeast(0)
		.floorDiv(info.interval)
		.coerceAtMost(info.thumbnailCount.toLong() - 1)
		.toInt()
	val tilesPerSheet = info.tileWidth * info.tileHeight
	val sheetIndex = tile / tilesPerSheet
	val tileOffset = tile % tilesPerSheet
	val tileOffsetX = tileOffset % info.tileWidth
	val tileOffsetY = tileOffset / info.tileWidth

	return TrickplayImage(
		sheet = getTrickplayTileSheet(api, sourceUuid, info, sheetIndex, api.trickplayHeaders()),
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
	val sheetCount = ((info.thumbnailCount.toLong() + tilesPerSheet - 1) / tilesPerSheet).toInt()
	if (sheetCount <= 0) return emptyList()

	val headers = api.trickplayHeaders()
	return (0 until sheetCount).map { sheetIndex ->
		getTrickplayTileSheet(api, sourceUuid, info, sheetIndex, headers)
	}
}

private fun BaseItemDto.getTrickplayTileSheet(
	api: ApiClient,
	sourceId: UUID,
	info: TrickplayInfoDto,
	sheetIndex: Int,
	headers: NetworkHeaders,
): TrickplayTileSheet {
	return TrickplayTileSheet(
		url = api.trickPlayApi.getTrickplayTileImageUrl(
			itemId = id,
			width = info.width,
			index = sheetIndex,
			mediaSourceId = sourceId,
		),
		headers = headers,
		columns = info.tileWidth,
		rows = info.tileHeight,
	)
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
		!isValidTrickplayGeometry(info.width, info.height, info.tileWidth, info.tileHeight, info.thumbnailCount)
	) return null

	return sourceId to info
}

internal fun isValidTrickplayGeometry(
	width: Int,
	height: Int,
	columns: Int,
	rows: Int,
	thumbnailCount: Int,
): Boolean =
	width > 0 && height > 0 && columns > 0 && rows > 0 && thumbnailCount > 0 &&
		columns.toLong() * rows <= TRICKPLAY_SHEET_MAX_PIXELS &&
		width.toLong() * columns <= Int.MAX_VALUE &&
		height.toLong() * rows <= Int.MAX_VALUE

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
