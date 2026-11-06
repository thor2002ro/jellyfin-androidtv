package org.jellyfin.androidtv.ui.home

import android.app.Activity
import android.content.Context
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.livetv.liveTvActionButtons
import org.jellyfin.androidtv.ui.presentation.ActionButtonPresenter
import org.jellyfin.androidtv.ui.presentation.ActionButtonSize
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.OrderedListRow
import org.jellyfin.androidtv.ui.presentation.addOrderedRow

class HomeFragmentLiveTVRow(
	private val activity: Activity,
	private val userRepository: UserRepository,
) : HomeFragmentRow {
	override fun addToRowsAdapter(
		context: Context,
		cardPresenter: CardPresenter,
		rowsAdapter: MutableObjectAdapter<Row>,
		rowOrder: Double,
	) {
		val header = HeaderItem(rowsAdapter.size().toLong(), activity.getString(R.string.pref_live_tv_cat))
		val adapter = ArrayObjectAdapter(ActionButtonPresenter(ActionButtonSize.SINGLE))
		liveTvActionButtons(activity, userRepository.currentUser.value).forEach(adapter::add)

		rowsAdapter.addOrderedRow(OrderedListRow(header, adapter, rowOrder))
	}
}
