package auth.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepositoryImpl
import org.jellyfin.androidtv.auth.repository.SessionRepositoryState
import org.jellyfin.androidtv.auth.repository.shouldClearStreamBadgeCaches
import org.jellyfin.androidtv.auth.store.AuthenticationPreferences
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.DeviceInfo
import java.util.UUID

class SessionRepositoryTests : FunSpec({
	test("ending a session clears active API credentials without touching saved authentication") {
		val authenticationPreferences = mockk<AuthenticationPreferences>()
		val authenticationStore = mockk<AuthenticationStore>()
		val api = mockk<ApiClient>(relaxed = true)
		val defaultDeviceInfo = mockk<DeviceInfo>()
		val repository = SessionRepositoryImpl(
			authenticationPreferences = authenticationPreferences,
			authenticationStore = authenticationStore,
			userApiClient = api,
			preferencesRepository = mockk(),
			defaultDeviceInfo = defaultDeviceInfo,
			userRepository = mockk(relaxed = true),
			serverRepository = mockk(relaxed = true),
			telemetryPreferences = mockk(),
		)

		repository.destroyCurrentSession()

		verify(exactly = 1) { api.update(baseUrl = null, accessToken = null, deviceInfo = defaultDeviceInfo) }
		verify { authenticationStore wasNot Called }
		verify { authenticationPreferences wasNot Called }
		repository.currentSession.value shouldBe null
		repository.state.value shouldBe SessionRepositoryState.READY
	}

	test("stream badge caches are kept for matching cold start session") {
		val serverId = UUID.randomUUID()
		val userId = UUID.randomUUID()
		val session = Session(userId = userId, serverId = serverId, accessToken = "token")

		shouldClearStreamBadgeCaches(
			previousSession = null,
			previousLastServerId = serverId,
			previousLastUserId = userId,
			nextSession = session,
		) shouldBe false
	}

	test("stream badge caches are cleared for different cold start session") {
		val session = Session(userId = UUID.randomUUID(), serverId = UUID.randomUUID(), accessToken = "token")

		shouldClearStreamBadgeCaches(
			previousSession = null,
			previousLastServerId = UUID.randomUUID(),
			previousLastUserId = session.userId,
			nextSession = session,
		) shouldBe true
		shouldClearStreamBadgeCaches(
			previousSession = null,
			previousLastServerId = session.serverId,
			previousLastUserId = UUID.randomUUID(),
			nextSession = session,
		) shouldBe true
	}

	test("stream badge caches are cleared for in-memory session switch") {
		shouldClearStreamBadgeCaches(
			previousSession = Session(userId = UUID.randomUUID(), serverId = UUID.randomUUID(), accessToken = "before"),
			previousLastServerId = null,
			previousLastUserId = null,
			nextSession = Session(userId = UUID.randomUUID(), serverId = UUID.randomUUID(), accessToken = "after"),
		) shouldBe true
	}
})
