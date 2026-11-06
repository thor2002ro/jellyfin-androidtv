package org.jellyfin.androidtv.ui.search

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto

class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
) {
	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())
	private val rowsByLabel = mutableMapOf<Int, ListRow>()
	private val itemsByLabel = mutableMapOf<Int, Collection<BaseItemDto>>()

	fun showResults(searchResultGroups: Collection<SearchResultGroup>) {
		val rows = mutableListOf<Row>()
		val labels = searchResultGroups.mapTo(mutableSetOf()) { it.labelRes }
		rowsByLabel.keys.filter { it !in labels }.forEach { label ->
			(rowsByLabel.remove(label)?.adapter as? ItemRowAdapter)?.cancelRetrieval()
			itemsByLabel.remove(label)
		}
		for ((labelRes, baseItems) in searchResultGroups) {
			if (itemsByLabel[labelRes] == baseItems) {
				rowsByLabel[labelRes]?.let(rows::add)
				continue
			}
			(rowsByLabel.remove(labelRes)?.adapter as? ItemRowAdapter)?.cancelRetrieval()
			itemsByLabel[labelRes] = baseItems
			if (baseItems.isEmpty()) continue
			val adapter = ItemRowAdapter(
				context,
				baseItems.toList(),
				CardPresenter(),
				null,
				QueryType.Search
			)
			val row = ListRow(HeaderItem(labelRes.toLong(), context.getString(labelRes)), adapter)
			adapter.setRow(row)
			adapter.Retrieve()
			rowsByLabel[labelRes] = row
			rows.add(row)
		}
		rowsAdapter.replaceAll(
			rows,
			areItemsTheSame = { old, new -> old.headerItem.id == new.headerItem.id },
			areContentsTheSame = { old, new -> old === new },
		)
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (item !is BaseRowItem) return@OnItemViewClickedListener
		row as ListRow
		val adapter = row.adapter as ItemRowAdapter
		itemLauncher.launch(item as BaseRowItem?, adapter, context)
	}

	val onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
		val baseItem = item?.let { (item as BaseRowItem).baseItem }
		if (baseItem != null) {
			backgroundService.setBackground(baseItem)
		} else {
			backgroundService.clearBackgrounds()
		}
	}
}
