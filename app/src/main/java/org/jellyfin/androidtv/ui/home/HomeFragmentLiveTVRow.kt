package org.jellyfin.androidtv.ui.home

import android.app.Activity
import android.content.Context
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.livetv.liveTvActionButtons
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.LiveTvActionButtonPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import kotlin.math.roundToInt

class HomeFragmentLiveTVRow(
	private val activity: Activity,
	private val userRepository: UserRepository,
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val header = HeaderItem(rowsAdapter.size().toLong(), activity.getString(R.string.pref_live_tv_cat))
		val adapter = ArrayObjectAdapter(LiveTvActionButtonPresenter(
			context.dimenDp(R.dimen.live_tv_action_tile_width),
			context.dimenDp(R.dimen.live_tv_action_tile_height),
		))
		liveTvActionButtons(activity, userRepository.currentUser.value).forEach(adapter::add)

		rowsAdapter.add(ListRow(header, adapter))
	}

	private fun Context.dimenDp(resId: Int) = (resources.getDimension(resId) / resources.displayMetrics.density).roundToInt()
}
