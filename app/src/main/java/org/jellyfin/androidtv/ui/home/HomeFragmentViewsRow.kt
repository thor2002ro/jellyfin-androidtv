package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto

class HomeFragmentViewsRow(
	val small: Boolean,
	private val initialViews: Collection<BaseItemDto>,
) : HomeFragmentRow {
	private companion object {
		val smallCardPresenter = CardPresenter(true, 64)
		val largeCardPresenter = CardPresenter(true, 96)
	}

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val presenter = if (small) smallCardPresenter else largeCardPresenter
		val rowAdapter = ItemRowAdapter(context, GetUserViewsRequest, presenter, rowsAdapter).apply {
			initialUserViews = initialViews
		}

		val header = HeaderItem(context.getString(R.string.lbl_my_media))
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row, rowsAdapter.size().toDouble())
		rowsAdapter.add(row)
		rowAdapter.Retrieve()
	}
}
