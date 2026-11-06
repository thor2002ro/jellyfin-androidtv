package org.jellyfin.androidtv.ui.browsing

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.jellyfin.androidtv.ui.navigation.RouteContext
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import java.util.UUID

fun BrowseGridFragment.createSettingsVisibility() = MutableStateFlow(false)

fun BrowseGridFragment.createFiltersVisibility() = MutableStateFlow(false)

fun BrowseGridFragment.loadLibraryPreferences(
	preferencesRepository: PreferencesRepository,
	preferencesId: String,
	callback: (LibraryPreferences) -> Unit,
) {
	lifecycleScope.launch {
		callback(preferencesRepository.getLibraryPreferencesAsync(preferencesId))
	}
}

fun BrowseGridFragment.addFilters(
	view: ComposeView,
	visible: MutableStateFlow<Boolean>,
	initialFilters: () -> FilterOptions,
	parentId: UUID,
	includeTypes: Set<BaseItemKind>,
	onApply: (FilterOptions) -> Unit,
) {
	view.setContent {
		val api = koinInject<ApiClient>()
		val isVisible by visible.collectAsState(false)
		var state by remember { mutableStateOf(LibraryFilterDialogState()) }
		var cachedChoices by remember(parentId, includeTypes) { mutableStateOf<LibraryFilterChoices?>(null) }
		val filterRoutes: Map<String, RouteComposable> = remember {
			mapOf(FILTER_ROUTE to { _: RouteContext -> })
		}

		LaunchedEffect(isVisible, parentId, includeTypes) {
			if (!isVisible) return@LaunchedEffect
			val applied = initialFilters()
			state = LibraryFilterDialogState(
				applied = applied,
				draft = applied,
				choices = cachedChoices ?: LibraryFilterChoices(),
				loading = cachedChoices == null,
				videoFiltersAvailable = supportsVideoFilters(includeTypes),
			)
			if (cachedChoices == null) {
				val loadedChoices = loadLibraryFilterChoices(api, parentId, includeTypes)
				if (shouldCacheLibraryFilterChoices(loadedChoices)) cachedChoices = loadedChoices
				state = state.copy(
					choices = loadedChoices,
					loading = false,
				)
			}
		}

		ProvideRouter(filterRoutes, FILTER_ROUTE) {
			SettingsDialog(
				visible = isVisible,
				onDismissRequest = {
					state = state.dismiss()
					visible.value = false
				},
			) {
				key(isVisible) {
					LibraryFilterDialog(
						state = state,
						onStateChange = { state = it },
						onApply = { filters ->
							onApply(filters)
							visible.value = false
						},
						onClear = { filters ->
							onApply(filters)
							visible.value = false
						},
					)
				}
			}
		}
	}
}

private const val FILTER_ROUTE = "/library-filters"

fun BrowseGridFragment.addSettings(
	view: ComposeView,
	itemId: UUID,
	displayPreferencesId: String,
	visible: MutableStateFlow<Boolean>,
) {
	view.setContent {
		val isVisible by visible.collectAsState(false)

		ProvideRouter(
			routes,
			Routes.LIBRARIES_DISPLAY,
			mapOf("itemId" to itemId.toString(), "displayPreferencesId" to displayPreferencesId)
		) {
			SettingsDialog(
				visible = isVisible,
				onDismissRequest = {
					visible.value = false
					onResume()
				}
			) {
				SettingsRouterContent()
			}
		}
	}
}
