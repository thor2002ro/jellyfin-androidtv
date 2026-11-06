package org.jellyfin.androidtv.ui.startup.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.androidtv.auth.model.ServerVersionNotSupported
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.databinding.FragmentServerBinding
import org.jellyfin.androidtv.ui.card.UserCardView
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.ListAdapter
import org.jellyfin.androidtv.util.MarkdownRenderer
import org.jellyfin.androidtv.util.createBundle
import org.jellyfin.androidtv.util.getConnectionErrorMessage
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.math.ceil

class ServerFragment : Fragment() {
	companion object {
		const val ARG_SERVER_ID = "server_id"
	}

	private val startupViewModel: StartupViewModel by activityViewModel()
	private val markdownRenderer: MarkdownRenderer by inject()
	private val backgroundService: BackgroundService by inject()
	private var _binding: FragmentServerBinding? = null
	private val binding get() = _binding!!

	private val serverIdArgument get() = arguments?.getString(ARG_SERVER_ID)?.ifBlank { null }?.toUUIDOrNull()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		val server = serverIdArgument?.let(startupViewModel::getServer)

		if (server == null) {
			navigateFragment<SelectServerFragment>(keepToolbar = true, keepHistory = false)
			return null
		}

		_binding = FragmentServerBinding.inflate(inflater, container, false)

		val userAdapter = UserAdapter(requireContext(), server, startupViewModel)
		userAdapter.onAddUserPressed = { openAddUser(server) }
		userAdapter.onItemPressed = profileSelection@ { user, card ->
			if (card.loading) return@profileSelection
			card.loading = true
			val startupActivity = requireActivity() as StartupActivity
			startupActivity.profileLoginOrigin = card.loginTransitionOrigin()
			startupViewModel.authenticate(server, user).onEach { state ->
				card.loading = state == AuthenticatingState || state == AuthenticatedState
				if (state != AuthenticatingState && state != AuthenticatedState && state != RequireSignInState) {
					startupActivity.profileLoginOrigin = null
				}
				when (state) {
					// Ignored states
					AuthenticatingState -> Unit
					AuthenticatedState -> Unit
					// Actions
					RequireSignInState -> navigateFragment<UserLoginFragment>(createBundle {
						putString(UserLoginFragment.ARG_SERVER_ID, server.id.toString())
						putString(UserLoginFragment.ARG_USERNAME, user.name)
					})
					// Errors
					is ServerUnavailableState -> Toast.makeText(
						context,
						state.error?.getConnectionErrorMessage(requireContext())
							?: getString(R.string.server_connection_failed),
						Toast.LENGTH_LONG
					).show()

					is ApiClientErrorLoginState -> Toast.makeText(
						context,
						state.error.getConnectionErrorMessage(requireContext()),
						Toast.LENGTH_LONG
					).show()

					is ServerVersionNotSupported -> Toast.makeText(
						context,
						getString(
							R.string.server_issue_outdated_version,
							state.server.version,
							ServerRepository.recommendedServerVersion.toString()
						),
						Toast.LENGTH_LONG
					).show()
				}
			}.launchIn(lifecycleScope)
		}
		binding.users.adapter = userAdapter
		binding.users.setGravity(Gravity.CENTER_HORIZONTAL)
		binding.root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
			if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateUserGrid()
		}
		binding.notification.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
			if (bottom - top != oldBottom - oldTop) updateUserGrid()
		}

		startupViewModel.users
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.onEach { users ->
				// Keep the same profile selected when the server refreshes or reorders the user list.
				val hadSelection = binding.users.selectedPosition in userAdapter.items.indices
				val selectedUser = userAdapter.items.getOrNull(binding.users.selectedPosition)
				// The trailing null is Add Account, which stays available even when there are no profiles.
				userAdapter.items = users + listOf(null)
				if (hadSelection) {
					val position = userAdapter.items.indexOfFirst { it?.id == selectedUser?.id }
					binding.users.setSelectedPosition(position.coerceAtLeast(0))
				} else binding.users.setSelectedPosition(0)

				binding.noUsersWarning.isVisible = users.isEmpty()
				val currentBinding = binding
				// Wait for the updated content to be laid out, and ignore callbacks from a screen that was closed.
				binding.root.post {
					if (_binding === currentBinding) {
						updateUserGrid()
						if (!hadSelection) binding.users.requestFocus()
					}
				}
			}.launchIn(viewLifecycleOwner.lifecycleScope)

		startupViewModel.serverConnectionError
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.onEach { error -> updateNotification(server, error?.takeIf { it.serverId == server.id }?.error) }
			.launchIn(viewLifecycleOwner.lifecycleScope)

		startupViewModel.loadUsers(server)

		onServerChange(server)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				val updated = startupViewModel.updateServer(server)
				if (updated) startupViewModel.getServer(server.id)?.let(::onServerChange)
			}
		}

		return binding.root
	}

	override fun onDestroyView() {
		super.onDestroyView()

		_binding = null
	}

	private fun onServerChange(server: Server) {
		binding.loginDisclaimer.text = server.loginDisclaimer?.let { markdownRenderer.toMarkdownSpanned(it) }
		binding.loginDisclaimer.isVisible = !server.loginDisclaimer.isNullOrBlank()

		binding.serverButton.apply {
			text = server.name
			contentDescription = "${server.name}, ${server.address}"
		}

		binding.serverButton.setOnClickListener {
			(requireActivity() as StartupActivity).profileLoginOrigin = null
			navigateFragment<SelectServerFragment>(keepToolbar = true)
		}

		updateNotification(
			server,
			startupViewModel.serverConnectionError.value
				?.takeIf { it.serverId == server.id }
				?.error,
		)
	}

	private fun openAddUser(server: Server) {
		(requireActivity() as StartupActivity).profileLoginOrigin = null
		navigateFragment<UserLoginFragment>(createBundle {
			putString(UserLoginFragment.ARG_SERVER_ID, server.id.toString())
		})
	}

	private fun updateUserGrid() {
		val binding = _binding ?: return
		val density = Density(requireContext())
		val cardWidth = with(density) { UserCardView.CARD_WIDTH.roundToPx() }
		val spacing = with(density) { 12.dp.roundToPx() }
		val availableWidth = binding.root.width - binding.root.paddingLeft - binding.root.paddingRight
		if (availableWidth <= 0) return
		val itemCount = binding.users.adapter?.itemCount ?: 1
		// Center small groups and wrap larger lists, with at most five profiles across for comfortable TV viewing.
		val columns = ((availableWidth + spacing) / (cardWidth + spacing)).coerceIn(1, 5)
			.coerceAtMost(itemCount.coerceAtLeast(1))
		val rows = (itemCount + columns - 1) / columns
		// Convert the shared dp sizes and sp label height together so the grid matches Compose at any font size.
		val cardHeight = with(density) {
			val bodyHeight = UserCardView.AVATAR_SIZE + UserCardView.VERTICAL_PADDING * 2 + UserCardView.NAME_SPACING
			ceil(bodyHeight.toPx() + UserCardView.NAME_LINE_HEIGHT.toPx()).toInt()
		}
		// Leave room for the heading and server controls; extra profile rows can scroll inside the grid.
		val otherViews = listOf(binding.title, binding.noUsersWarning, binding.serverButton, binding.loginDisclaimer)
		val occupiedHeight = otherViews.filter { it.isVisible }.sumOf {
			val margins = it.layoutParams as ViewGroup.MarginLayoutParams
			it.height + margins.topMargin + margins.bottomMargin
		} + (binding.notification.height.takeIf { binding.notification.isVisible } ?: 0)
		val availableHeight = binding.root.height - binding.root.paddingTop - binding.root.paddingBottom -
			occupiedHeight - with(density) { 24.dp.roundToPx() }
		binding.users.setNumColumns(columns)
		binding.users.setColumnWidth(cardWidth)
		binding.users.setHorizontalSpacing(spacing)
		binding.users.setVerticalSpacing(spacing)
		binding.users.updateLayoutParams {
			width = (columns * cardWidth + (columns - 1) * spacing).coerceAtMost(availableWidth)
			height = (rows * cardHeight + (rows - 1) * spacing).coerceAtMost(availableHeight.coerceAtLeast(cardHeight))
		}
	}

	private fun updateNotification(server: Server, connectionError: Throwable?) {
		if (connectionError != null) {
			binding.notification.isVisible = true
			binding.notification.text = connectionError.getConnectionErrorMessage(requireContext())
		} else if (!server.versionSupported) {
			binding.notification.isVisible = true
			binding.notification.text = getString(
				R.string.server_unsupported_notification,
				server.version,
				ServerRepository.recommendedServerVersion.toString(),
			)
		} else if (!server.setupCompleted) {
			binding.notification.isVisible = true
			binding.notification.text = getString(R.string.server_setup_incomplete)
		} else {
			binding.notification.isGone = true
		}
	}

	private inline fun <reified F : Fragment> navigateFragment(
		args: Bundle = createBundle(),
		keepToolbar: Boolean = false,
		keepHistory: Boolean = true,
	) {
		requireActivity()
			.supportFragmentManager
			.commit {
				if (keepToolbar) {
					replace<StartupToolbarFragment>(R.id.content_view)
					add<F>(R.id.content_view, null, args)
				} else {
					replace<F>(R.id.content_view, null, args)
				}

				if (keepHistory) addToBackStack(null)
			}
	}

	override fun onResume() {
		super.onResume()

		startupViewModel.reloadStoredServers()
		backgroundService.clearBackgrounds()

		val server = serverIdArgument?.let(startupViewModel::getServer)
		if (server != null) startupViewModel.loadUsers(server)
		else navigateFragment<SelectServerFragment>(keepToolbar = true)
	}

	private class UserAdapter(
		private val context: Context,
		private val server: Server,
		private val startupViewModel: StartupViewModel,
	) : ListAdapter<User?, UserAdapter.ViewHolder>() {
		var onItemPressed: (User, UserCardView) -> Unit = { _, _ -> }
		var onAddUserPressed: () -> Unit = {}

		override fun areItemsTheSame(old: User?, new: User?): Boolean = old?.id == new?.id

		override fun onViewRecycled(holder: ViewHolder) {
			holder.cardView.loading = false
			super.onViewRecycled(holder)
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val cardView = UserCardView(context).apply {
				layoutParams = RecyclerView.LayoutParams(
					with(Density(context)) { UserCardView.CARD_WIDTH.roundToPx() },
					ViewGroup.LayoutParams.WRAP_CONTENT,
				)
			}

			return ViewHolder(cardView)
		}

		override fun onBindViewHolder(holder: ViewHolder, item: User?) {
			holder.cardView.imageIcon = if (item == null) R.drawable.ic_user_add else null
			if (item == null) {
				holder.cardView.name = context.getString(R.string.add_user)
				holder.cardView.contentDescription = holder.cardView.name
				holder.cardView.image = null
				holder.cardView.setOnLongClickListener(null)
				holder.cardView.setOnClickListener { onAddUserPressed() }
				return
			}
			holder.cardView.name = item.name
			holder.cardView.contentDescription = item.name
			holder.cardView.image = startupViewModel.getUserImage(server, item)

			holder.cardView.setPopupMenu {
				// Logout button
				if (item is PrivateUser && item.accessToken != null) {
					item(context.getString(R.string.lbl_sign_out)) {
						startupViewModel.logoutUser(item, server)
					}
				}

				// Remove button
				if (item is PrivateUser) {
					item(context.getString(R.string.lbl_remove)) {
						startupViewModel.deleteUser(item, server)
					}
				}
			}

			holder.cardView.setOnClickListener {
				onItemPressed(item, holder.cardView)
			}
		}

		private class ViewHolder(
			val cardView: UserCardView,
		) : RecyclerView.ViewHolder(cardView)
	}
}
