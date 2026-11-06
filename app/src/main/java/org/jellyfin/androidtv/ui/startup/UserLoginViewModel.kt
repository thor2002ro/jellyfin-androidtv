package org.jellyfin.androidtv.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.AutomaticAuthenticateMethod
import org.jellyfin.androidtv.auth.model.ConnectedQuickConnectState
import org.jellyfin.androidtv.auth.model.CredentialAuthenticateMethod
import org.jellyfin.androidtv.auth.model.ErrorQuickConnectState
import org.jellyfin.androidtv.auth.model.LoginState
import org.jellyfin.androidtv.auth.model.PendingQuickConnectState
import org.jellyfin.androidtv.auth.model.QuickConnectAuthenticateMethod
import org.jellyfin.androidtv.auth.model.QuickConnectState
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.UnavailableQuickConnectState
import org.jellyfin.androidtv.auth.model.UnknownQuickConnectState
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.util.sdk.forUser
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class UserLoginViewModel(
	jellyfin: Jellyfin,
	private val serverRepository: ServerRepository,
	private val authenticationRepository: AuthenticationRepository,
	private val defaultDeviceInfo: DeviceInfo,
) : ViewModel() {
	private val _loginState = MutableStateFlow<LoginState?>(null)
	val loginState = _loginState.asStateFlow()

	var forcedUsername: String? = null

	private val _server = MutableStateFlow<Server?>(null)
	val server = _server.asStateFlow()

	private val quickConnectApi = jellyfin.createApi()
	private var quickConnectSecret: String? = null
	private var quickConnectPollingJob: Job? = null
	private val _quickConnectState = MutableStateFlow<QuickConnectState>(UnknownQuickConnectState)
	val quickConnectState = _quickConnectState.asStateFlow()
	fun authenticate(server: Server, user: User): Flow<LoginState> =
		authenticationRepository.authenticate(server, AutomaticAuthenticateMethod(user))

	fun login(username: String, password: String) {
		val server = server.value ?: return
		_loginState.value = AuthenticatingState
		authenticationRepository.authenticate(server, CredentialAuthenticateMethod(username, password)).onEach {
			_loginState.value = it
		}.launchIn(viewModelScope)
	}

	fun clearLoginState() {
		_loginState.value = null
		resetQuickConnect()
	}

	private fun resetQuickConnect() {
		_quickConnectState.value = UnknownQuickConnectState
		cancelQuickConnect()
	}

	fun cancelQuickConnect() {
		quickConnectPollingJob?.cancel()
		quickConnectPollingJob = null
		quickConnectSecret = null
	}

	/**
	 * Start a new Quick Connect flow. Does nothing when already active.
	 */
	suspend fun initiateQuickconnect() {
		// Already initialized
		if (quickConnectState.value !is UnknownQuickConnectState && quickConnectState.value !is ErrorQuickConnectState) return

		val server = server.value ?: return
		quickConnectPollingJob?.cancel()
		_quickConnectState.emit(UnknownQuickConnectState)
		quickConnectSecret = null

		try {
			val response = withContext(Dispatchers.IO) {
				quickConnectApi.update(
					baseUrl = server.address,
					deviceInfo = defaultDeviceInfo.forUser(UUID.randomUUID()),
				)

				quickConnectApi.quickConnectApi.initiateQuickConnect().content
			}

			quickConnectSecret = response.secret
			_quickConnectState.emit(PendingQuickConnectState(response.code))
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to initiate QuickConnect for server ${server.address}")
			if (err.isQuickConnectUnavailable()) _quickConnectState.emit(UnavailableQuickConnectState)
			else _quickConnectState.emit(ErrorQuickConnectState(err))
			return
		}

		// Update every 5 seconds until QuickConnect is inactive or the view model is cancelled
		quickConnectPollingJob = viewModelScope.launch {
			while (isActive) {
				delay(5.seconds)
				if (!updateQuickConnectState()) break
			}
		}
	}

	/**
	 * Update the Quick Connect state.
	 *
	 * @return true when Quick Connect is active, false when inactive.
	 */
	private suspend fun updateQuickConnectState(): Boolean {
		val server = server.value ?: return false
		val secret = quickConnectSecret ?: return false

		try {
			val state = withContext(Dispatchers.IO) {
				quickConnectApi.quickConnectApi.getQuickConnectState(secret = secret).content
			}

			if (state.authenticated) {
				_quickConnectState.emit(ConnectedQuickConnectState)

				authenticationRepository.authenticate(server, QuickConnectAuthenticateMethod(state.secret)).collect {
					_loginState.emit(it)
				}

				quickConnectPollingJob = null
				return false
			} else {
				_quickConnectState.emit(PendingQuickConnectState(state.code))
				return true
			}
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to update QuickConnect state for server ${server.address}")
			if (err.isQuickConnectUnavailable()) _quickConnectState.emit(UnavailableQuickConnectState)
			else _quickConnectState.emit(ErrorQuickConnectState(err))
			quickConnectPollingJob = null
			return false
		}
	}

	fun setServer(id: UUID?) {
		_server.value = serverRepository.storedServers.value
			.firstOrNull { it.id == id }
	}
}

private fun ApiClientException.isQuickConnectUnavailable(): Boolean =
	this is InvalidStatusException && status in QUICK_CONNECT_UNAVAILABLE_STATUSES

private val QUICK_CONNECT_UNAVAILABLE_STATUSES = setOf(
	HTTP_STATUS_BAD_REQUEST,
	HTTP_STATUS_UNAUTHORIZED,
	HTTP_STATUS_FORBIDDEN,
	HTTP_STATUS_NOT_FOUND,
	HTTP_STATUS_METHOD_NOT_ALLOWED,
)

private const val HTTP_STATUS_BAD_REQUEST = 400
private const val HTTP_STATUS_UNAUTHORIZED = 401
private const val HTTP_STATUS_FORBIDDEN = 403
private const val HTTP_STATUS_NOT_FOUND = 404
private const val HTTP_STATUS_METHOD_NOT_ALLOWED = 405
