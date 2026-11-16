package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import kotlin.math.min

internal fun createBrowseGridAdapter(
	context: Context,
	row: BrowseRowDef,
	presenter: CardPresenter,
	cardsScreenEstimate: Int,
	cardsScreenStride: Int,
): ItemRowAdapter {
	val chunkSize = if (cardsScreenEstimate > 0 && cardsScreenEstimate >= row.chunkSize) {
		min(cardsScreenEstimate + cardsScreenStride, 150)
	} else row.chunkSize
	return when (row.queryType) {
		QueryType.NextUp -> ItemRowAdapter(context, row.nextUpQuery, true, presenter, null)
		QueryType.Views -> ItemRowAdapter(context, GetUserViewsRequest, presenter, null)
		QueryType.SimilarSeries -> ItemRowAdapter(context, row.similarQuery, QueryType.SimilarSeries, presenter, null)
		QueryType.SimilarMovies -> ItemRowAdapter(context, row.similarQuery, QueryType.SimilarMovies, presenter, null)
		QueryType.LiveTvChannel -> ItemRowAdapter(context, row.tvChannelQuery, 40, presenter, null)
		QueryType.LiveTvProgram -> ItemRowAdapter(context, row.programQuery, presenter, null, row.liveTvProgramSelectAction)
		QueryType.LiveTvRecording -> ItemRowAdapter(context, row.recordingQuery, chunkSize, presenter, null)
		QueryType.Artists -> ItemRowAdapter(context, row.artistsQuery, chunkSize, presenter, null)
		QueryType.AlbumArtists -> ItemRowAdapter(context, row.albumArtistsQuery, chunkSize, presenter, null)
		else -> ItemRowAdapter(context, row.query, chunkSize, row.preferParentThumb, row.isStaticHeight, presenter, null)
	}
}
