package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.view.View
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.roundToInt

class HomeFragmentBrowseRowDefRow(
	private val browseRowDef: BrowseRowDef,
	private val channelCardLongClick: ((item: Any?, view: View) -> Boolean)? = null,
) : HomeFragmentRow, KoinComponent {
	private val userPreferences by inject<UserPreferences>()

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val header = HeaderItem(browseRowDef.headerText)
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		// Some of these members are probably never used and could be removed
		val rowAdapter = when (browseRowDef.queryType) {
			QueryType.NextUp -> ItemRowAdapter(context, browseRowDef.nextUpQuery, preferParentThumb, cardPresenter, rowsAdapter)
			QueryType.LatestItems -> ItemRowAdapter(context, browseRowDef.latestItemsQuery, userPreferences[UserPreferences.seriesThumbnailsEnabled], cardPresenter, rowsAdapter)
			QueryType.Views -> ItemRowAdapter(context, GetUserViewsRequest, cardPresenter, rowsAdapter)
			QueryType.SimilarSeries -> ItemRowAdapter(context, browseRowDef.similarQuery, QueryType.SimilarSeries, cardPresenter, rowsAdapter)
			QueryType.SimilarMovies -> ItemRowAdapter(context, browseRowDef.similarQuery, QueryType.SimilarMovies, cardPresenter, rowsAdapter)
			QueryType.LiveTvChannel -> ItemRowAdapter(context, browseRowDef.tvChannelQuery, 40, cardPresenter, rowsAdapter)
			QueryType.LiveTvProgram -> ItemRowAdapter(
				context,
				browseRowDef.programQuery,
				if (browseRowDef.useChannelCards) CardPresenter(false, ImageType.THUMB, context.dimenDp(R.dimen.live_tv_card_height), channelCardLongClick) else cardPresenter,
				rowsAdapter,
				browseRowDef.liveTvProgramSelectAction,
			)
			QueryType.LiveTvRecording -> ItemRowAdapter(context, browseRowDef.recordingQuery, browseRowDef.chunkSize, cardPresenter, rowsAdapter)
			QueryType.Resume -> ItemRowAdapter(context, browseRowDef.resumeQuery, browseRowDef.chunkSize, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight, CardPresenter(true, 96), rowsAdapter)
			else -> ItemRowAdapter(context, browseRowDef.query, browseRowDef.chunkSize, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight, cardPresenter, rowsAdapter, browseRowDef.queryType)
		}

		rowAdapter.setReRetrieveTriggers(browseRowDef.changeTriggers)
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row, rowsAdapter.size().toDouble())
		rowAdapter.Retrieve()
		rowsAdapter.add(row)
	}

	private fun Context.dimenDp(resId: Int) = (resources.getDimension(resId) / resources.displayMetrics.density).roundToInt()
}
