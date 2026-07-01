package org.jellyfin.androidtv.ui.composable.compat

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.browsing.DestinationFragmentView
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

private val BackToExitTimeout = 2.seconds

@Composable
fun AppNavigationHost(
	modifier: Modifier = Modifier,
	navigationRepository: NavigationRepository = koinInject(),
) {
	val factory = remember { AppNavigationHostViewFactory() }
	val activity = LocalActivity.current
	val coroutineScope = rememberCoroutineScope()
	val mediaToastRegistry = remember { MediaToastRegistry(coroutineScope) }
	val windowInfo = LocalWindowInfo.current
	var backToExitDeadline by remember { mutableStateOf(0L) }

	val canGoBack by remember {
		navigationRepository.currentAction.map { navigationRepository.canGoBack }.distinctUntilChanged()
	}.collectAsState(navigationRepository.canGoBack)

	BackHandler(canGoBack) { navigationRepository.goBack() }
	BackHandler(!canGoBack && windowInfo.isWindowFocused) {
		val now = SystemClock.elapsedRealtime()
		if (now <= backToExitDeadline) {
			backToExitDeadline = 0L
			activity?.finishAfterTransition()
			return@BackHandler
		}

		backToExitDeadline = now + BackToExitTimeout.inWholeMilliseconds
		mediaToastRegistry.emit(
			icon = R.drawable.ic_stop,
			text = R.string.app_back_to_exit,
			duration = BackToExitTimeout,
		)
	}

	LaunchedEffect(canGoBack) {
		if (canGoBack) backToExitDeadline = 0L
	}

	AndroidView(
		factory = factory,
		modifier = modifier,
	)

	MediaToasts(mediaToastRegistry)

	LaunchedEffect(Unit) {
		navigationRepository.currentAction.collect { action ->
			when (action) {
				is NavigationAction.NavigateFragment -> factory.view.navigate(action)
				NavigationAction.GoBack -> factory.view.goBack()
				NavigationAction.Nothing -> Unit
			}
		}
	}
}

private class AppNavigationHostViewFactory : (Context) -> View {
	private var _view: DestinationFragmentView? = null

	val view get() = requireNotNull(_view)

	override operator fun invoke(
		context: Context
	): View = DestinationFragmentView(context).also { view ->
		_view = view
	}
}
