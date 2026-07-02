package org.jellyfin.androidtv.ui.settings.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun MainActivitySettings() {
	val viewModel = koinActivityViewModel<SettingsViewModel>()
	val visible by viewModel.visible.collectAsState()
	val route by viewModel.route.collectAsState()

	ProvideRouter(routes, Routes.MAIN) {
		val router = LocalRouter.current
		LaunchedEffect(visible, route) {
			if (visible) {
				router.replace(Routes.MAIN)
				if (route != Routes.MAIN) router.push(route)
			}
		}

		SettingsDialog(
			visible = visible,
			onDismissRequest = { viewModel.hide() }
		) {
			SettingsRouterContent()
		}
	}
}
