package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.os.Handler
import android.view.View
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import androidx.lifecycle.Lifecycle
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.LocalDateTime

class BrowseRecordingsFragment : EnhancedBrowseFragment() {
	override fun onResume() {
		super.onResume()
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		mTitle.setText(R.string.lbl_loading_elipses)
	}

	override fun setupQueries(rowLoader: RowLoader) {
		showViews = true

		mRows.add(BrowseRowDef(getString(R.string.lbl_recent_recordings), BrowsingUtils.createLiveTVRecordingsRequest(40), 40))

		val moviesDef = BrowseRowDef(getString(R.string.lbl_movies), BrowsingUtils.createLiveTVMovieRecordingsRequest(), 60)
		val showsDef = BrowseRowDef(getString(R.string.lbl_tv_series), BrowsingUtils.createLiveTVSeriesRecordingsRequest(), 60)

		mRows.add(showsDef)
		mRows.add(moviesDef)
		mRows.add(BrowseRowDef(getString(R.string.lbl_sports), BrowsingUtils.createLiveTVSportsRecordingsRequest(), 60))
		mRows.add(BrowseRowDef(getString(R.string.lbl_kids), BrowsingUtils.createLiveTVKidsRecordingsRequest(), 60))

		rowLoader.loadRows(mRows)
		addNext24Timers()
	}

	private fun addNext24Timers() {
		getLiveTvTimers({ timers ->
			val next24 = LocalDateTime.now().plusDays(1)
			val nearTimers = timers.items
				.filter { timer -> timer.startDate?.isBefore(next24) == true }
				.map(::getTimerProgramInfo)

			if (nearTimers.isNotEmpty()) {
				val scheduledAdapter = ItemRowAdapter(requireContext(), nearTimers, mCardPresenter, mRowsAdapter, true)
				scheduledAdapter.Retrieve()
				val scheduleRow = ListRow(HeaderItem(getString(R.string.scheduled_in_next_24_hours)), scheduledAdapter)
				mRowsAdapter.add(0, scheduleRow)
				Handler().postDelayed({
					if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@postDelayed

					mRowsFragment.setSelectedPosition(0, true)
				}, 500)
			}
		}, { exception ->
			Timber.e(exception, "Failed to get Live TV recordings / timers")
		})
	}

	override fun addAdditionalRows(rowAdapter: MutableObjectAdapter<Row>) {
		val gridHeader = HeaderItem(rowAdapter.size().toLong(), getString(R.string.lbl_views))

		val gridRowAdapter = ArrayObjectAdapter(GridButtonPresenter())
		gridRowAdapter.add(GridButton(SCHEDULE, getString(R.string.lbl_schedule)))
		gridRowAdapter.add(GridButton(SERIES, getString(R.string.lbl_series_recordings)))
		rowAdapter.add(ListRow(gridHeader, gridRowAdapter))
	}
}
