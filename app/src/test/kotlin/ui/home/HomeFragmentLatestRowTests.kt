package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.model.api.CollectionType

class HomeFragmentLatestRowTests : FunSpec({
	test("latest video-like rows request stream badge fields") {
		latestMediaFields(CollectionType.MOVIES) shouldBe ItemRepository.streamBadgeFields
		latestMediaFields(CollectionType.TVSHOWS) shouldBe ItemRepository.streamBadgeFields
	}

	test("latest non-video rows stay light") {
		latestMediaFields(null) shouldBe ItemRepository.browseFields
		latestMediaFields(CollectionType.MUSIC) shouldBe ItemRepository.browseFields
		latestMediaFields(CollectionType.PHOTOS) shouldBe ItemRepository.browseFields
	}
})
