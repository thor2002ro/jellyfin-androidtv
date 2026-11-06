package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jellyfin.androidtv.constant.CustomMessage
import kotlin.time.Duration.Companion.seconds

class CustomMessageRepositoryTests : FunSpec({
	test("the same event can be delivered more than once") {
		runBlocking {
			val repository = CustomMessageRepositoryImpl()
			val received = mutableListOf<CustomMessage>()
			val collector = launch(start = CoroutineStart.UNDISPATCHED) {
				repository.message.filterNotNull().take(2).toList(received)
			}

			repository.pushMessage(CustomMessage.RefreshHomeConfiguration)
			yield()
			repository.pushMessage(CustomMessage.RefreshHomeConfiguration)

			withTimeout(5.seconds) { collector.join() }
			received shouldBe listOf(
				CustomMessage.RefreshHomeConfiguration,
				CustomMessage.RefreshHomeConfiguration,
			)
		}
	}
})
