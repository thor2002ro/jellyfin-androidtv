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
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.dimenDp
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class HomeFragmentBrowseRowDefRow(
	private val browseRowDef: BrowseRowDef,
	private val channelCardLongClick: ((item: Any?, view: View) -> Boolean)? = null,
) : HomeFragmentRow, KoinComponent {
	private val userSettingPreferences by inject<UserSettingPreferences>()

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val header = HeaderItem(browseRowDef.headerText)
		val preferParentThumb = userSettingPreferences[UserSettingPreferences.seriesThumbnailsEnabled]

		// Some of these members are probably never used and could be removed
		val rowAdapter = when (browseRowDef.queryType) {
			QueryType.NextUp -> ItemRowAdapter(context, browseRowDef.nextUpQuery, preferParentThumb, cardPresenter, rowsAdapter).apply {
				combinedResumeQuery = browseRowDef.supplementalResumeQuery
				nextUpQueryProvider = {
					browseRowDef.nextUpQuery.withHomeNextUpCutoff(userSettingPreferences[UserSettingPreferences.homeNextUpMaxDays])
				}
			}
			QueryType.LatestItems -> ItemRowAdapter(context, browseRowDef.latestItemsQuery, preferParentThumb, cardPresenter, rowsAdapter)
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
			QueryType.Resume -> createResumeHomeRowAdapter(
				context = context,
				browseRowDef = browseRowDef,
				cardPresenter = cardPresenter,
				rowsAdapter = rowsAdapter,
				preferParentThumb = browseRowDef.preferParentThumb && preferParentThumb,
			)
			else -> ItemRowAdapter(context, browseRowDef.query, browseRowDef.chunkSize, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight, cardPresenter, rowsAdapter, browseRowDef.queryType)
		}

		rowAdapter.setReRetrieveTriggers(browseRowDef.changeTriggers)
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row, rowsAdapter.size().toDouble())
		rowsAdapter.add(row)
		rowAdapter.Retrieve()
	}
}

internal fun createResumeHomeRowAdapter(
	context: Context,
	browseRowDef: BrowseRowDef,
	cardPresenter: CardPresenter,
	rowsAdapter: MutableObjectAdapter<Row>,
	preferParentThumb: Boolean = browseRowDef.preferParentThumb,
) = ItemRowAdapter(
	context,
	browseRowDef.resumeQuery,
	browseRowDef.chunkSize,
	preferParentThumb,
	browseRowDef.isStaticHeight,
	cardPresenter,
	rowsAdapter,
)
