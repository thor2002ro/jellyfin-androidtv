package org.jellyfin.androidtv.ui.presentation

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.RecyclerView

/** Replace cached pixel measurements without replacing adapters or fetching their data. */
internal fun RecyclerView.recreatePresentation() {
	val data = adapter ?: return
	val state = layoutManager?.onSaveInstanceState()
	val hadFocus = hasFocus()
	val pools = linkedSetOf<RecyclerView.RecycledViewPool>()
	fun collectPools(view: View) {
		if (view is RecyclerView) pools.add(view.recycledViewPool)
		if (view is ViewGroup) repeat(view.childCount) { collectPools(view.getChildAt(it)) }
	}
	collectPools(this)
	adapter = null
	pools.forEach(RecyclerView.RecycledViewPool::clear)
	adapter = data
	state?.let { layoutManager?.onRestoreInstanceState(it) }
	if (hadFocus) requestFocus()
}

/** Pooled views do not receive configuration events while detached. */
internal fun ComposeView.syncConfigurationOnAttach() {
	var density = resources.configuration.densityDpi
	var fontScale = resources.configuration.fontScale
	addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
		override fun onViewDetachedFromWindow(view: View) = Unit
		override fun onViewAttachedToWindow(view: View) {
			val config = resources.configuration
			if (density == config.densityDpi && fontScale == config.fontScale) return
			density = config.densityDpi
			fontScale = config.fontScale
			dispatchConfigurationChanged(config)
		}
	})
}
