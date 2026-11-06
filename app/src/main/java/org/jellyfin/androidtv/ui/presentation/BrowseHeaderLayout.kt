package org.jellyfin.androidtv.ui.presentation

import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.composable.modifier.fadingEdges
import org.jellyfin.androidtv.ui.shared.toolbar.HeaderBackdropLayout

fun configureBrowseBackground(view: ComposeView, visible: Boolean) {
	view.visibility = if (visible) View.VISIBLE else View.GONE
	if (visible) {
		view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		view.setContent { JellyfinTheme { AppBackground() } }
	} else {
		view.setContent { }
		view.disposeComposition()
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowseHeaderLayout(headerHeight: Int, content: @Composable () -> Unit) {
	val headerPixels = with(LocalDensity.current) { headerHeight.dp.toPx() }
	val defaultScrollSpec = LocalBringIntoViewSpec.current
	val scrollSpec = remember(defaultScrollSpec, headerPixels) {
		BrowseHeaderBringIntoViewSpec(defaultScrollSpec, headerPixels)
	}
	HeaderBackdropLayout(Modifier.fillMaxWidth().height(headerHeight.dp + 24.dp).fadingEdges(bottom = 24.dp)) {
		AppBackground()
		CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) { content() }
	}
}

internal class BrowseHeaderBringIntoViewSpec(
	private val delegate: BringIntoViewSpec,
	private val headerHeight: Float,
) : BringIntoViewSpec {
	override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
		delegate.calculateScrollDistance(offset - headerHeight, size, (containerSize - headerHeight).coerceAtLeast(0f))
}
