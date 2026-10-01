package org.jellyfin.androidtv.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.auth.repository.SessionRepositoryState

class LeanbackChannelWorkerReadinessTests : FunSpec({
	test("channel updates wait until the authenticated session is ready") {
		canUpdateLeanbackChannels(
			isSupported = true,
			apiUsable = true,
			sessionState = SessionRepositoryState.RESTORING_SESSION,
			hasSession = false,
		) shouldBe false

		canUpdateLeanbackChannels(
			isSupported = true,
			apiUsable = true,
			sessionState = SessionRepositoryState.SWITCHING_SESSION,
			hasSession = true,
		) shouldBe false

		canUpdateLeanbackChannels(
			isSupported = true,
			apiUsable = true,
			sessionState = SessionRepositoryState.READY,
			hasSession = true,
		) shouldBe true
	}
})
