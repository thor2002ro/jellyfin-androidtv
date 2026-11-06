package org.jellyfin.androidtv.ui.presentation

import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.Row

class OrderedListRow(header: HeaderItem, adapter: ObjectAdapter, val order: Double) : ListRow(header, adapter)

internal fun MutableObjectAdapter<Row>.addOrderedRow(row: OrderedListRow) {
	val index = indexOfFirst { it is OrderedListRow && it.order > row.order }
	if (index < 0) add(row) else add(index, row)
}
