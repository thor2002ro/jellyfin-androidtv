package auth.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.shouldClearStreamBadgeCaches
import java.util.UUID

class SessionRepositoryTests : FunSpec({
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
