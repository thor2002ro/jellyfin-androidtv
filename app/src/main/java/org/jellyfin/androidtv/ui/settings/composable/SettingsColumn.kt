package org.jellyfin.androidtv.ui.settings.composable

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import org.jellyfin.androidtv.ui.base.list.LocalListButtonFocusHandler
import org.jellyfin.androidtv.ui.navigation.LocalRouteContext
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.design.Tokens

@Composable
fun SettingsColumn(content: LazyListScope.() -> Unit) {
	val router = LocalRouter.current
	val routeContext = LocalRouteContext.current
	var restoredKey by remember(routeContext) { mutableStateOf<Long?>(null) }

	CompositionLocalProvider(
		LocalListButtonFocusHandler provides { key: Long, focusRequester: FocusRequester, focused: Boolean ->
			val context = routeContext ?: return@provides
			if (focused) {
				router.saveFocusedKey(context, key)
				restoredKey = key
			} else if (router.backStack.lastOrNull() == context && router.focusedKey(context) == key && restoredKey != key) {
				if (runCatching { focusRequester.requestFocus() }.getOrDefault(false)) {
					restoredKey = key
				}
			}
		}
	) {
		LazyColumn(
			modifier = Modifier
				.focusGroup()
				.padding(horizontal = Tokens.Space.spaceMd, vertical = Tokens.Space.spaceSm),
			verticalArrangement = Arrangement.spacedBy(Tokens.Space.space2xs),
			content = content,
		)
	}
}
