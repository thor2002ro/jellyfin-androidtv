package org.jellyfin.androidtv.ui.browsing

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideLocalInteractionTracker
import org.jellyfin.androidtv.ui.composable.compat.AppNavigationHost
import org.jellyfin.androidtv.ui.home.HomeFragment
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.screensaver.InAppScreensaver
import org.jellyfin.androidtv.ui.settings.compat.MainActivitySettings
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.ui.shared.transition.ProfileLoginAnimation
import org.jellyfin.androidtv.ui.shared.transition.ProfileLoginOrigin
import org.jellyfin.androidtv.ui.shared.transition.ProfileLoginOverlay
import org.jellyfin.androidtv.updater.AppUpdatePrompt
import org.jellyfin.androidtv.util.applyTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import kotlin.reflect.KClass

class MainActivity : FragmentActivity() {
	private val navigationRepository by inject<NavigationRepository>()
	private val sessionRepository by inject<SessionRepository>()
	private val userRepository by inject<UserRepository>()
	private val interactionTrackerViewModel by viewModel<InteractionTrackerViewModel>()
	private val mediaManager by inject<MediaManager>()
	private val workManager by inject<WorkManager>()
	private var authenticationRedirectStarted = false
	internal var profileLoginAnimation by mutableStateOf<ProfileLoginAnimation?>(null)
		private set

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()

		super.onCreate(savedInstanceState)

		if (!validateAuthentication()) return

		observeAuthentication()

		interactionTrackerViewModel.keepScreenOn.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { keepScreenOn ->
				if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
				else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			}.launchIn(lifecycleScope)

		if (savedInstanceState == null && navigationRepository.canGoBack) navigationRepository.reset(clearHistory = true)
		// A screen recreation or a deep link opens normally; this motion belongs only to the login-to-Home handoff.
		if (savedInstanceState == null && navigationRepository.currentDestination.value == Destinations.home) {
			profileLoginAnimation = ProfileLoginOrigin.readFrom(intent)?.let(::ProfileLoginAnimation)
		}

		navigationRepository.currentAction
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach {
				interactionTrackerViewModel.notifyInteraction(canCancel = false, userInitiated = false)
			}.launchIn(lifecycleScope)

		setContent {
			JellyfinTheme {
				ProvideLocalInteractionTracker(
					interactionTracker = { interactionTrackerViewModel.notifyInteraction(false, userInitiated = true) }
				) {
					val destination by navigationRepository.currentDestination.collectAsState()
					if (shouldDrawActivityBackground(destination.fragment)) AppBackground()
					Box(Modifier.fillMaxSize()) {
						AppNavigationHost(navigationRepository = navigationRepository)
					}
					profileLoginAnimation?.let { animation ->
						// Draw Home behind the cover so its first render does not consume the movement animation.
						Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - animation.homeRevealProgress }.background(Color.Black)) {
							AppBackground()
						}
					}
					InAppScreensaver()
					MainActivitySettings()
					AppUpdatePrompt()
					profileLoginAnimation?.let { animation ->
						ProfileLoginOverlay(animation) { profileLoginAnimation = null }
					}
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()

		if (!validateAuthentication()) return

		applyTheme()

		interactionTrackerViewModel.activityPaused = false
	}

	private fun observeAuthentication() {
		sessionRepository.currentSession
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach { session -> if (session == null) validateAuthentication() }
			.launchIn(lifecycleScope)

		userRepository.currentUser
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach { user -> if (user == null) validateAuthentication() }
			.launchIn(lifecycleScope)
	}

	private fun validateAuthentication(): Boolean {
		if (sessionRepository.currentSession.value == null || userRepository.currentUser.value == null) {
			if (authenticationRedirectStarted) return false
			authenticationRedirectStarted = true
			Timber.w("Activity ${this::class.qualifiedName} started without a session, bouncing to StartupActivity")
			startActivity(Intent(this, StartupActivity::class.java))
			finish()
			return false
		}

		return true
	}

	override fun onPause() {
		super.onPause()

		interactionTrackerViewModel.activityPaused = true
	}

	override fun onUserLeaveHint() {
		super.onUserLeaveHint()
		val destination = navigationRepository.currentDestination.value
		if (!Destinations.isPlayback(destination)) return

		if (Destinations.isAudioPlayback(destination)) mediaManager.stopAudio(releasePlayer = true)
		if (!navigationRepository.goBack()) {
			navigationRepository.reset(Destinations.home)
		}
	}

	override fun onStop() {
		super.onStop()

		LeanbackChannelWorker.enqueueOneTime(workManager)

		lifecycleScope.launch(Dispatchers.IO) {
			Timber.i("MainActivity stopped")
			sessionRepository.restoreSession(destroyOnly = true)
		}
	}

	// Forward key events to fragments

	private fun Fragment.onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		var result = childFragmentManager.fragments.any { it.onKeyEvent(keyCode, event) }
		if (!result && this is View.OnKeyListener) result = onKey(currentFocus, keyCode, event)
		return result
	}

	private fun onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean = supportFragmentManager.fragments
		.any { it.onKeyEvent(keyCode, event) }

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyDown(keyCode, event)

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)

	override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)
}

internal fun shouldDrawActivityBackground(fragment: KClass<out Fragment>): Boolean =
	fragment != HomeFragment::class && fragment != BrowseGridFragment::class
