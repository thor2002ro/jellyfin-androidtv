package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.navigation.Destination
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.util.UUID

object ItemLauncherHelper {
	private val preferencesRepository by KoinJavaComponent.inject<PreferencesRepository>(PreferencesRepository::class.java)
	private val navigationRepository by KoinJavaComponent.inject<NavigationRepository>(NavigationRepository::class.java)

	@JvmStatic
	fun getItem(itemId: UUID, callback: Response<BaseItemDto>) {
		ProcessLifecycleOwner.get().lifecycleScope.launch {
			val api by KoinJavaComponent.inject<ApiClient>(ApiClient::class.java)

			try {
				val response = withContext(Dispatchers.IO) {
					api.userLibraryApi.getItem(itemId = itemId).content
				}
				callback.onResponse(response)
			} catch (error: ApiClientException) {
				callback.onError(error)
			}
		}
	}

	@JvmStatic
	fun launchUserView(context: Context, baseItem: BaseItemDto?) {
		val owner = context.getActivity() as? LifecycleOwner ?: return
		owner.lifecycleScope.launch {
			try {
				navigationRepository.navigate(getUserViewDestination(baseItem))
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				Timber.w(error, "Unable to open user view ${baseItem?.id}")
			}
		}
	}

	suspend fun getUserViewDestination(baseItem: BaseItemDto?): Destination.Fragment {
		if (baseItem == null) return Destinations.home

		val useSmartScreen = when (baseItem.collectionType ?: CollectionType.UNKNOWN) {
			CollectionType.MOVIES,
			CollectionType.TVSHOWS -> {
				val preferencesId = baseItem.displayPreferencesId
				if (preferencesId == null) false
				else preferencesRepository.getLibraryPreferencesAsync(preferencesId)
					.get(LibraryPreferences.enableSmartScreen)
			}
			CollectionType.MUSIC,
			CollectionType.LIVETV -> true
			else -> false
		}

		return if (useSmartScreen) Destinations.librarySmartScreen(baseItem)
		else Destinations.libraryBrowser(baseItem)
	}
}
