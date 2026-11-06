package org.jellyfin.androidtv.data.eventhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.ItemFields
import java.util.UUID

class SocketHandlerTests : FunSpec({
	test("stream badge invalidation batches changed items into lean Jellyfin requests") {
		val changedIds = List(101) { UUID.randomUUID() }.toSet()

		val requests = createSeriesStreamBadgeInvalidationRequests(changedIds)

		requests.size shouldBe 2
		requests[0].ids?.size shouldBe 100
		requests[1].ids?.size shouldBe 1
		requests.forEach { request ->
			request.fields shouldBe setOf(ItemFields.PARENT_ID)
			request.limit shouldBe request.ids?.size
			request.enableImages shouldBe false
			request.enableUserData shouldBe false
			request.enableTotalRecordCount shouldBe false
		}
	}
})
