package org.jellyfin.androidtv.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.koin.android.ext.android.inject

fun Fragment.toggleGuideChannelFavorite(header: GuideChannelHeader) {
	val itemMutationRepository by inject<ItemMutationRepository>()

	lifecycleScope.launch {
		runCatching {
			val userData = itemMutationRepository.setFavorite(
				item = header.channel.id,
				favorite = !header.isFavorite(),
			)

			header.channel = header.channel.copy(userData = userData)
			TvManager.updateChannelUserData(header.channel.id, userData)
			header.setFavorite(userData.isFavorite)
		}
	}
}
