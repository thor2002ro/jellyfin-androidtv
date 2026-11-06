package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import io.mockk.mockk
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.Row
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.OrderedListRow
import org.jellyfin.androidtv.ui.presentation.addOrderedRow

class HomeRowOrderTests : FunSpec({
	test("slow library rows keep configured order around independent and Live TV rows") {
		val rows = spyk(MutableObjectAdapter<Row>(), recordPrivateCalls = true)
		every { rows["notifyItemRangeInserted"](any<Int>(), any<Int>()) } returns Unit
		val resume = OrderedListRow(HeaderItem("Resume"), ArrayObjectAdapter(), 1.0)
		val live = OrderedListRow(HeaderItem("Live TV"), ArrayObjectAdapter(), 3.0)
		val libraries = OrderedListRow(HeaderItem("Libraries"), ArrayObjectAdapter(), 0.0)
		val latest = OrderedListRow(HeaderItem("Latest"), ArrayObjectAdapter(), 2.0)
		listOf(resume, live, libraries, latest).forEach(rows::addOrderedRow)
		rows.toList() shouldBe listOf(libraries, resume, latest, live)
	}
	test("a refilled row returns before static Live TV actions without duplicating itself") {
		val rows = spyk(MutableObjectAdapter<Row>(), recordPrivateCalls = true)
		every { rows["notifyItemRangeInserted"](any<Int>(), any<Int>()) } returns Unit
		val context = mockk<Context> {
			every { getString(R.string.lbl_empty) } returns "Empty"
		}
		val items = ItemRowAdapter(context, emptyList(), mockk<Presenter>(), rows, QueryType.Search)
		val resume = OrderedListRow(HeaderItem("Resume"), items, 1.0)
		val live = OrderedListRow(HeaderItem("Live TV"), ArrayObjectAdapter(), 3.0)
		items.setRow(resume, 1.0)
		items.itemsLoaded = 1
		rows.addOrderedRow(live)

		items.addRowToParentIfResultsReceived()
		items.addRowToParentIfResultsReceived()

		rows.toList() shouldBe listOf(resume, live)
	}

})
