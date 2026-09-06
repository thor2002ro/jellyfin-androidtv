package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter

class BrowseScheduleFragment : EnhancedBrowseFragment() {
	override fun onResume() {
		super.onResume()
	}

	override fun setupQueries(rowLoader: RowLoader) {
		TvManager.getScheduleRowsAsync(this, null, CardPresenter(true), mRowsAdapter)
	}
}
