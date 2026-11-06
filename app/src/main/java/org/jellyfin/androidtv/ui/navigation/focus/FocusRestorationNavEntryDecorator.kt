package org.jellyfin.androidtv.ui.navigation.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavEntryDecorator
import org.jellyfin.androidtv.ui.navigation.RouteContext

internal const val INITIAL_FOCUS_KEY_METADATA = "initialFocusKey"

@Composable
fun rememberFocusRestorationNavEntryDecorator(): NavEntryDecorator<RouteContext> {
	val savedFocusKeys = rememberSaveable { mutableMapOf<Any, String?>() }

	return remember {
		NavEntryDecorator(
			onPop = { key -> savedFocusKeys.remove(key) },
			decorate = { entry ->
				val key = entry.contentKey
				val focusPersistManager = remember(key) { FocusPersistManager() }

				CompositionLocalProvider(
					LocalFocusPersistManager provides focusPersistManager
				) {
					entry.Content()
				}

				LaunchedEffect(key) {
					val focusKey = if (savedFocusKeys.containsKey(key)) savedFocusKeys[key] else entry.metadata[INITIAL_FOCUS_KEY_METADATA] as? String
					focusPersistManager.restore(focusKey)
				}

				DisposableEffect(key) {
					onDispose {
						savedFocusKeys[key] = focusPersistManager.save()
					}
				}
			}
		)
	}
}
