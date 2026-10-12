package org.jellyfin.androidtv.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.androidtv.auth.model.AuthenticationSortBy
import org.jellyfin.androidtv.auth.model.AutomaticAuthenticateMethod
import org.jellyfin.androidtv.auth.model.LoginState
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.ServerUserRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.store.AuthenticationPreferences
import org.jellyfin.sdk.api.client.exception.ApiClientException
import timber.log.Timber
import java.util.UUID

class StartupViewModel(
	private val serverRepository: ServerRepository,
	private val serverUserRepository: ServerUserRepository,
	private val authenticationRepository: AuthenticationRepository,
	private val sessionRepository: SessionRepository,
	private val authenticationPreferences: AuthenticationPreferences,
) : ViewModel() {
	val storedServers = serverRepository.storedServers
	val discoveredServers = serverRepository.discoveredServers

	private val _users = MutableStateFlow<List<User>>(emptyList())
	val users = _users.asStateFlow()

	private val _serverConnectionError = MutableStateFlow<ServerConnectionError?>(null)
	val serverConnectionError = _serverConnectionError.asStateFlow()

	private val userComparator = compareByDescending<User> { user ->
		if (
			authenticationPreferences[AuthenticationPreferences.sortBy] == AuthenticationSortBy.LAST_USE &&
			user is PrivateUser
		) user.lastUsed
		else null
	}.thenBy { user -> user.name }

	private val discoveryMutex = Mutex()
	private var loadUsersJob: Job? = null

	fun getServer(id: UUID) = serverRepository.storedServers.value
		.find { it.id == id }

	fun loadUsers(server: Server) {
		loadUsersJob?.cancel()
		loadUsersJob = viewModelScope.launch {
			if (_serverConnectionError.value?.serverId == server.id) _serverConnectionError.value = null
			val storedUsers = serverUserRepository.getStoredServerUsers(server)
			_users.value = storedUsers.sortedWith(userComparator)

			val storedUserIds = storedUsers.map { it.id }
			val publicUsers = try {
				serverUserRepository.getPublicServerUsers(server)
					.filterNot { it.id in storedUserIds }
			} catch (err: ApiClientException) {
				Timber.e(err, "Unable to load users from server ${server.address}")
				_serverConnectionError.value = ServerConnectionError(server.id, err)
				emptyList()
			}
			_users.value = (storedUsers + publicUsers).sortedWith(userComparator)
		}
	}

	fun addServer(address: String) = serverRepository.addServer(address)

	fun deleteServer(serverId: UUID) {
		viewModelScope.launch {
			if (serverRepository.deleteServer(serverId)) {
				authenticationPreferences.clearServer(serverId)
				if (sessionRepository.currentSession.value?.serverId == serverId) {
					sessionRepository.destroyCurrentSession()
				}
			}
		}
	}

	fun deleteUser(user: PrivateUser, server: Server) {
		viewModelScope.launch {
			if (serverUserRepository.deleteStoredUser(user)) {
				authenticationPreferences.clearUser(user.serverId, user.id)
				if (sessionRepository.currentSession.value?.let { it.serverId == user.serverId && it.userId == user.id } == true) {
					sessionRepository.destroyCurrentSession()
				}
			}
			loadUsers(server)
		}
	}

	fun logoutUser(user: PrivateUser, server: Server) {
		viewModelScope.launch {
			if (authenticationRepository.logout(user)) {
				authenticationPreferences.clearUser(user.serverId, user.id)
				loadUsers(server)
			}
		}
	}

	fun authenticate(server: Server, user: User): Flow<LoginState> =
		authenticationRepository.authenticate(server, AutomaticAuthenticateMethod(user))

	fun getUserImage(server: Server, user: User): String? =
		authenticationRepository.getUserImageUrl(server, user)

	fun loadDiscoveryServers() {
		// Only run one discovery process at a time
		if (discoveryMutex.isLocked) return

		viewModelScope.launch(Dispatchers.IO) {
			discoveryMutex.withLock {
				serverRepository.loadDiscoveryServers()
			}
		}
	}

	fun reloadStoredServers() {
		viewModelScope.launch { serverRepository.loadStoredServers() }
	}

	suspend fun getLastServer(): Server? {
		serverRepository.loadStoredServers()
		return serverRepository.storedServers.value.maxByOrNull { it.dateLastAccessed }
	}

	suspend fun updateServer(server: Server): Boolean = serverRepository.updateServer(server)
}

data class ServerConnectionError(
	val serverId: UUID,
	val error: ApiClientException,
)
