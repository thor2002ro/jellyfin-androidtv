package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.leanback.widget.VerticalGridView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

class HomeRowsFragmentTests : FunSpec({
	test("density refreshes coalesce without discarding loaded Home rows") {
		val fragment = HomeRowsFragment()
		mockkObject(fragment)
		try {
			val config = mockk<Configuration>().apply { densityDpi = 320; fontScale = 1f }
			val resources = mockk<Resources> { every { configuration } returns config }
			val callbacks = mutableListOf<Runnable>()
			val grid = mockk<VerticalGridView>(relaxed = true) {
				every { childCount } returns 0
				every { post(any()) } answers { callbacks.add(firstArg()); true }
			}
			every { fragment.resources } returns resources
			every { fragment.verticalGridView } returns grid
			HomeRowsFragment::class.java.getDeclaredField("homeRowsLoaded").apply { isAccessible = true }.set(fragment, true)

			fragment.onConfigurationChanged(config)
			fragment.onConfigurationChanged(config)
			callbacks.forEach(Runnable::run)

			verify(exactly = 1) { grid.adapter = null }
			HomeRowsFragment::class.java.getDeclaredField("homeRowsLoaded").apply { isAccessible = true }.get(fragment) shouldBe true
		} finally {
			unmockkObject(fragment)
		}
	}

	test("tracking Home rows for cleanup does not require a view or start updates") {
		val fragment = HomeRowsFragment()
		val rowAdapter = ItemRowAdapter(mockk<Context>(), emptyList(), mockk<Presenter>(), null, QueryType.Search)
		val rowsAdapter = mockk<MutableObjectAdapter<Row>>().apply {
			every { size() } returns 1
			every { get(0) } returns ListRow(rowAdapter)
		}
		Class.forName("androidx.leanback.app.BaseRowSupportFragment")
			.getDeclaredField("mAdapter").apply { isAccessible = true }.set(fragment, rowsAdapter)

		HomeRowsFragment::class.java.getDeclaredMethod("trackHomeRows").apply { isAccessible = true }.invoke(fragment)

		val tracked = HomeRowsFragment::class.java.getDeclaredField("homeRowAdapters").apply { isAccessible = true }
		tracked.get(fragment) shouldBe setOf(rowAdapter)
	}
})
