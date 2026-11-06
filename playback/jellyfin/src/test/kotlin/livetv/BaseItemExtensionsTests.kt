package org.jellyfin.playback.jellyfin.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class BaseItemExtensionsTests : FunSpec({
	test("liveTvChannelId resolves channels and programs") {
		val channelId = UUID.randomUUID()
		val fallbackChannelId = UUID.randomUUID()
		BaseItemDto(id = channelId, type = BaseItemKind.TV_CHANNEL).liveTvChannelId() shouldBe channelId
		BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.PROGRAM,
			parentId = channelId,
			channelId = fallbackChannelId,
		).liveTvChannelId() shouldBe channelId
		BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.LIVE_TV_PROGRAM,
			channelId = fallbackChannelId,
		).liveTvChannelId() shouldBe fallbackChannelId
		BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE).liveTvChannelId() shouldBe null
	}
})
