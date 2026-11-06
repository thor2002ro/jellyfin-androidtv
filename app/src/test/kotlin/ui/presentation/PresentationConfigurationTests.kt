package org.jellyfin.androidtv.ui.presentation

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Parcelable
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.RecyclerView
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder

class PresentationConfigurationTests : FunSpec({
	test("refreshing presentation preserves the data adapter and scroll state") {
		val adapter = mockk<RecyclerView.Adapter<RecyclerView.ViewHolder>>()
		var currentAdapter: RecyclerView.Adapter<*>? = adapter
		val savedState = mockk<Parcelable>()
		val manager = mockk<RecyclerView.LayoutManager>(relaxed = true) {
			every { onSaveInstanceState() } returns savedState
		}
		val pool = mockk<RecyclerView.RecycledViewPool>(relaxed = true)
		val grid = mockk<RecyclerView>(relaxed = true) {
			every { this@mockk.adapter } answers { currentAdapter }
			every { this@mockk.adapter = any() } answers { currentAdapter = firstArg() }
			every { layoutManager } returns manager
			every { recycledViewPool } returns pool
			every { childCount } returns 0
		}

		grid.recreatePresentation()

		grid.adapter shouldBe adapter
		verifyOrder {
			grid.adapter = null
			pool.clear()
			grid.adapter = adapter
			manager.onRestoreInstanceState(savedState)
		}
	}

	test("a pooled Compose view receives a missed density change once on reattachment") {
		val config = mockk<Configuration>().apply { densityDpi = 213; fontScale = 1f }
		val resources = mockk<Resources> { every { configuration } returns config }
		val listener = slot<View.OnAttachStateChangeListener>()
		val view = mockk<ComposeView>(relaxed = true) {
			every { this@mockk.resources } returns resources
			every { addOnAttachStateChangeListener(capture(listener)) } returns Unit
		}

		view.syncConfigurationOnAttach()
		listener.isCaptured shouldBe true
		listener.captured.onViewAttachedToWindow(view)
		verify(exactly = 0) { view.dispatchConfigurationChanged(any()) }

		config.densityDpi = 320
		listener.captured.onViewAttachedToWindow(view)
		listener.captured.onViewAttachedToWindow(view)
		verify(exactly = 1) { view.dispatchConfigurationChanged(config) }
		config.fontScale = 1.2f
		listener.captured.onViewAttachedToWindow(view)
		verify(exactly = 2) { view.dispatchConfigurationChanged(config) }
		verify(exactly = 0) { view.disposeComposition() }
	}

	test("nested recycled pools are cleared without replacing their data adapters") {
		val childPool = mockk<RecyclerView.RecycledViewPool>(relaxed = true)
		val child = mockk<RecyclerView>(relaxed = true) {
			every { recycledViewPool } returns childPool
			every { childCount } returns 0
		}
		val adapter = mockk<RecyclerView.Adapter<RecyclerView.ViewHolder>>()
		val grid = mockk<RecyclerView>(relaxed = true) {
			every { this@mockk.adapter } returns adapter
			every { childCount } returns 1
			every { getChildAt(0) } returns child
		}

		grid.recreatePresentation()

		verifyOrder {
			grid.adapter = null
			childPool.clear()
			grid.adapter = adapter
		}
		verify(exactly = 0) { child.adapter = any() }
	}
})
