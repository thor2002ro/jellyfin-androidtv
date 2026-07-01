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

@Suppress("unused")
class SessionInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		TrackSelectionManager.initialize(TrackSelectionStore(context))
		LiveTvTrackCache.initialize(context)
		SeriesStreamBadgeCache.initialize(context)

		val koin = AppInitializer.getInstance(context)
			.initializeComponent(KoinInitializer::class.java)
			.koin

		// Restore system session
		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
			koin.get<SessionRepository>().restoreSession(destroyOnly = false)
		}
	}

	override fun dependencies() = listOf(KoinInitializer::class.java)
}
