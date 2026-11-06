package org.jellyfin.updater

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield

class AppUpdateCheckTest : FunSpec({
	test("reopening settings shares the active check and exposes its result to both callers") {
		coroutineScope {
			val finish = CompletableDeferred<Unit>()
			var requests = 0
			val check = AppUpdateCheck(this) {
				requests++
				finish.await()
				UpdateCheckResult.NoUpdate
			}
			val first = async(start = CoroutineStart.UNDISPATCHED) { check.run(true) }
			val reopened = async(start = CoroutineStart.UNDISPATCHED) { check.run(true) }
			yield()
			requests shouldBe 1
			check.state.value.checking shouldBe true
			finish.complete(Unit)
			first.await() shouldBe UpdateCheckResult.NoUpdate
			reopened.await() shouldBe UpdateCheckResult.NoUpdate
			check.state.value shouldBe AppUpdateCheckState(result = UpdateCheckResult.NoUpdate)
			check.reset()
			check.state.value.result shouldBe null
		}
	}

	test("cancelling one screen does not cancel the check another screen is awaiting") {
		coroutineScope {
			val finish = CompletableDeferred<Unit>()
			var requests = 0
			val check = AppUpdateCheck(this) {
				requests++
				finish.await()
				UpdateCheckResult.Failed("Offline")
			}
			val first = async(start = CoroutineStart.UNDISPATCHED) { check.run(true) }
			val second = async(start = CoroutineStart.UNDISPATCHED) { check.run(true) }
			first.cancel()
			finish.complete(Unit)
			second.await() shouldBe UpdateCheckResult.Failed("Offline")
			requests shouldBe 1
			check.state.value.checking shouldBe false
		}
	}

	test("a forced check does not reuse a skipped startup check") {
		coroutineScope {
			val finish = CompletableDeferred<Unit>()
			val requests = mutableListOf<Boolean>()
			val check = AppUpdateCheck(this) { force ->
				requests += force
				if (!force) finish.await()
				if (force) UpdateCheckResult.NoUpdate else UpdateCheckResult.Skipped
			}
			val startup = async(start = CoroutineStart.UNDISPATCHED) { check.run(false) }
			val manual = async(start = CoroutineStart.UNDISPATCHED) { check.run(true) }
			finish.complete(Unit)
			startup.await() shouldBe UpdateCheckResult.Skipped
			manual.await() shouldBe UpdateCheckResult.NoUpdate
			requests shouldBe listOf(false, true)
		}
	}
})
