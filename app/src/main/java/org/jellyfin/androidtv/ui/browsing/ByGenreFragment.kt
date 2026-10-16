package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

class ByGenreFragment : Fragment() {
	private val apiClient by inject<ApiClient>()
	private val navigation by inject<NavigationRepository>()
	private val folder by lazy { Json.decodeFromString<BaseItemDto>(requireArguments().getString(Extras.Folder)!!) }
	private val includeType by lazy { requireArguments().getString(Extras.IncludeType)?.let(BaseItemKind::fromNameOrNull) }
	private var genres by mutableStateOf<List<LibraryGenre>?>(null)
	private var loadFailed by mutableStateOf(false)
	private var selectedGenre: String? = null
	private val details = mutableMapOf<UUID, LibraryGenreDetails>()
	private val imageRequests = Semaphore(4)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		selectedGenre = savedInstanceState?.getString(SELECTED_GENRE)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putString(SELECTED_GENRE, selectedGenre)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		var retry by remember { mutableIntStateOf(0) }
		LaunchedEffect(retry) {
			if (genres != null && retry == 0) return@LaunchedEffect
			loadFailed = false
			genres = null
			try {
				genres = loadLibraryGenres(apiClient, folder.id, includeType)
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				Timber.e(exception, "Unable to load library genres")
				loadFailed = true
			}
		}
		JellyfinTheme {
			LibraryGenreScreen(
				genres = genres,
				loadFailed = loadFailed,
				selectedGenre = selectedGenre,
				onRetry = { retry++ },
				onSelect = { selectedGenre = it.id.toString() },
				onClick = {
					selectedGenre = it.id.toString()
					navigation.navigate(Destinations.libraryBrowser(folder, includeType?.serialName, FilterOptions(genreIds = it.ids)))
				},
				loadDetails = ::loadDetails,
			)
		}
	}

	private suspend fun loadDetails(genre: LibraryGenre): LibraryGenreDetails {
		details[genre.id]?.let { return it }
		return imageRequests.withPermit {
			try {
				loadLibraryGenreDetails(apiClient, folder.id, includeType, genre).also { details[genre.id] = it }
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				Timber.w(exception, "Unable to load genre count and artwork")
				LibraryGenreDetails(null, emptyList())
			}
		}
	}

	private companion object {
		const val SELECTED_GENRE = "selected_genre"
	}
}
