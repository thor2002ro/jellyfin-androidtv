package org.jellyfin.androidtv.ui.home

import android.content.Context
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest

class HomeCombinedContinueWatchingTests : FunSpec({
	test("combining replaces the first continue watching or next up section and removes the other") {
		val layout = createHomeSectionLayout(
			sections = listOf(
				HomeSectionType.LIBRARY_TILES_SMALL,
				HomeSectionType.NEXT_UP,
				HomeSectionType.LATEST_MEDIA,
				HomeSectionType.RESUME,
			),
			combineContinueWatchingAndNextUp = true,
		)

		layout.sections shouldBe listOf(
			HomeSectionType.LIBRARY_TILES_SMALL,
			HomeSectionType.RESUME,
			HomeSectionType.LATEST_MEDIA,
		)
		layout.combineContinueWatchingAndNextUp shouldBe true
	}

	test("combining leaves a single configured section unchanged") {
		val layout = createHomeSectionLayout(
			sections = listOf(HomeSectionType.RESUME, HomeSectionType.LATEST_MEDIA),
			combineContinueWatchingAndNextUp = true,
		)

		layout.sections shouldBe listOf(HomeSectionType.RESUME, HomeSectionType.LATEST_MEDIA)
		layout.combineContinueWatchingAndNextUp shouldBe false
	}

	test("disabled combining preserves separate section order") {
		val layout = createHomeSectionLayout(
			sections = listOf(HomeSectionType.NEXT_UP, HomeSectionType.RESUME),
			combineContinueWatchingAndNextUp = false,
		)

		layout.sections shouldBe listOf(HomeSectionType.NEXT_UP, HomeSectionType.RESUME)
		layout.combineContinueWatchingAndNextUp shouldBe false
	}

	test("combined row keeps movies in a resume request and next up episodes separate") {
		val context = mockk<Context>()
		every { context.getString(R.string.home_combined_continue_watching_next_up) } returns "Continue Watching & Next Up"
		val row = HomeFragmentHelper(
			context = context,
			userRepository = mockk<UserRepository>(),
			itemLimit = 25,
			includeNextUpRewatching = false,
		).loadResumeVideo(combineWithNextUp = true) as HomeFragmentBrowseRowDefRow
		val rowDefinition = row.privateField<BrowseRowDef>("browseRowDef")
		val nextUpRequest = rowDefinition.privateField<GetNextUpRequest>("nextUpItemsQuery")
		val resumeRequest = rowDefinition.privateField<GetResumeItemsRequest>("resumeItemsQuery")

		nextUpRequest.enableResumable shouldBe false
		resumeRequest.mediaTypes shouldBe listOf(MediaType.VIDEO)
		resumeRequest.limit shouldBe 25
		resumeRequest.enableTotalRecordCount shouldBe false
	}

	test("continue watching marks video rows as eligible for series thumbnails") {
		val context = mockk<Context>()
		every { context.getString(R.string.lbl_continue_watching) } returns "Continue Watching"
		val row = HomeFragmentHelper(
			context = context,
			userRepository = mockk<UserRepository>(),
			itemLimit = 25,
			includeNextUpRewatching = false,
		).loadResumeVideo() as HomeFragmentBrowseRowDefRow
		val rowDefinition = row.privateField<BrowseRowDef>("browseRowDef")

		rowDefinition.preferParentThumb shouldBe true
	}
})

private inline fun <reified T> Any.privateField(name: String): T {
	val field = javaClass.getDeclaredField(name).apply { isAccessible = true }
	return field.get(this) as T
}
