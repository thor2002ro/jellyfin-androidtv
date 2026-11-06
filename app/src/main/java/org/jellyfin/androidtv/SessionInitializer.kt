package org.jellyfin.androidtv

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.di.KoinInitializer
import org.jellyfin.androidtv.ui.itemhandling.SeriesStreamBadgeCache
import org.jellyfin.androidtv.ui.livetv.LiveTvTrackCache
import org.jellyfin.androidtv.util.TrackSelectionManager
import org.jellyfin.androidtv.util.TrackSelectionStore
import org.jellyfin.playback.core.font.SubtitleFontProvider

@Suppress("unused")
class SessionInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		val koin = AppInitializer.getInstance(context)
			.initializeComponent(KoinInitializer::class.java)
			.koin

		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
			SubtitleFontProvider.initialize(context)
		}

		// Hydrate saved caches before session restoration can clear data for a different user.
		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
			TrackSelectionManager.initialize(TrackSelectionStore(context))
			LiveTvTrackCache.initialize(context)
			SeriesStreamBadgeCache.initialize(context)
			koin.get<SessionRepository>().restoreSession(destroyOnly = false)
		}
	}

	override fun dependencies() = listOf(KoinInitializer::class.java)
}
