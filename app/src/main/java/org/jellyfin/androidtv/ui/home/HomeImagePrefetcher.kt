package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.ObjectAdapter
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.cardImageSize
import org.jellyfin.androidtv.ui.presentation.getDisplayConfig
import org.jellyfin.androidtv.ui.presentation.resolvedAspectRatio
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.sdk.api.client.ApiClient
import kotlin.math.roundToInt

internal class HomeImagePrefetcher(
	private val context: Context,
	private val scope: CoroutineScope,
	private val api: ApiClient,
	private val imageLoader: ImageLoader,
) {
	private val observers = mutableMapOf<ItemRowAdapter, ObjectAdapter.DataObserver>()
	private val jobs = mutableMapOf<ItemRowAdapter, Job>()
	// Limit image work across all observed rows while visible cards load.
	private val permits = Semaphore(3)

	fun observe(adapter: ItemRowAdapter) {
		if (adapter in observers) return
		val observer = object : ObjectAdapter.DataObserver() {
			override fun onChanged() = schedule(adapter)
		}
		observers[adapter] = observer
		adapter.registerObserver(observer)
		schedule(adapter)
	}

	private fun schedule(adapter: ItemRowAdapter) {
		jobs.remove(adapter)?.cancel()
		jobs[adapter] = scope.launch {
			// Let visible cards start first and coalesce row updates.
			delay(25)
			val density = context.resources.displayMetrics.density
			// Warm every card using the same sizes as the presenter.
			for (index in 0 until adapter.size()) {
				val item = adapter[index] as? BaseRowItem ?: continue
				val presenter = adapter.presenterSelector.getPresenter(item) as? CardPresenter ?: continue
				val config = item.getDisplayConfig(presenter.imageType, presenter.uniformAspect)
				val size = cardImageSize(item.staticHeight, presenter.staticHeight, config.resolvedAspectRatio())
				val imageWidth = (size.width.value * density).roundToInt().coerceAtLeast(1)
				val imageHeight = (size.height.value * density).roundToInt().coerceAtLeast(1)
				val image = config.image ?: continue
				val url = image.getUrl(api, imageWidth, imageHeight)
				permits.withPermit {
					imageLoader.execute(ImageRequest.Builder(context).data(url).size(imageWidth, imageHeight).build())
				}
			}
		}
	}

	// Detach observers and cancel requests when the home view is destroyed.
	fun close() {
		jobs.values.forEach(Job::cancel)
		jobs.clear()
		observers.forEach { (adapter, observer) -> adapter.unregisterObserver(observer) }
		observers.clear()
	}
}
