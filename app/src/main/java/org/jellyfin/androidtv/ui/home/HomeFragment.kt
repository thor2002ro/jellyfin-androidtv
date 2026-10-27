package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.composable.modifier.fadingEdges
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.androidtv.ui.shared.toolbar.HeaderBackdropLayout
import org.koin.android.ext.android.inject

class HomeFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()
	private val customMessageRepository by inject<CustomMessageRepository>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val rowsFocusRequester = remember { FocusRequester() }
		val profileTransitionActive = (LocalActivity.current as? MainActivity)?.profileLoginAnimation != null
		LaunchedEffect(rowsFocusRequester, profileTransitionActive) {
			if (!profileTransitionActive) rowsFocusRequester.requestFocus()
		}
		var homeRowsGeneration by remember { mutableIntStateOf(0) }
		LaunchedEffect(customMessageRepository) {
			customMessageRepository.message
				.drop(1)
				.collect { message ->
					if (shouldRecreateHomeRows(message)) homeRowsGeneration++
				}
		}

		var headerHeight by remember { mutableIntStateOf(0) }
		val headerHeightDp = with(LocalDensity.current) { headerHeight.toDp() }
		Box(Modifier.fillMaxSize()) {
			HeaderBackdropLayout(
				Modifier.fillMaxWidth().height(headerHeightDp + 12.dp).fadingEdges(bottom = 40.dp)
			) {
				AppBackground()

				// The leanback code has its own awful focus handling that doesn't work properly with Compose view inteop to workaround this
				// issue we add custom behavior that only allows focus exit when the current selected row is the first one. Additionally when
				// we do switch the focus, we reset the leanback state so it won't cause weird behavior when focus is regained
				key(homeRowsGeneration) {
					var rowsSupportFragment by remember { mutableStateOf<HomeRowsFragment?>(null) }
					LaunchedEffect(rowsSupportFragment, headerHeight) {
						rowsSupportFragment?.verticalGridView?.apply {
							// Reserve focus space without clipping rows as they scroll behind the header.
							clipToPadding = false
							updatePadding(top = headerHeight)
						}
					}
					AndroidFragment<HomeRowsFragment>(
						modifier = Modifier
							.focusGroup()
							.focusRequester(rowsFocusRequester)
							.focusProperties {
								onExit = {
									val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
									if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
										cancelFocusChange()
									} else {
										rowsSupportFragment?.selectedPosition = 0
										rowsSupportFragment?.verticalGridView?.clearFocus()
									}
								}
							}
							.fillMaxSize(),
						onUpdate = { fragment ->
							rowsSupportFragment = fragment
						}
					)
				}
			}

			Box(Modifier.onSizeChanged { headerHeight = it.height }) {
				MainToolbar(MainToolbarActiveButton.Home)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)
	}
}

internal fun shouldRecreateHomeRows(message: CustomMessage?) =
	message == CustomMessage.RefreshHomeConfiguration
