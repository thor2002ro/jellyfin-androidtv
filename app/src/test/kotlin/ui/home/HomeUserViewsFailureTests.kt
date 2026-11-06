package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.sdk.api.client.exception.ApiClientException

class HomeUserViewsFailureTests : FunSpec({
	test("the configured first section is published before later independent sections") {
		runBlocking {
			val events = mutableListOf<String>()
			loadHomeSections(
				sections = listOf(HomeSectionType.LIBRARY_BUTTONS, HomeSectionType.RESUME, HomeSectionType.LATEST_MEDIA, HomeSectionType.NEXT_UP),
				includeRecentlyReleased = true,
				loader = { events.add("views"); emptyList() },
			) { section, _ -> events.add(section.name) }
			events shouldBe listOf("views", "LIBRARY_BUTTONS", "RESUME", "LATEST_MEDIA", "NEXT_UP")
		}
	}

	test("independent sections survive User Views failure without adding dependent sections") {
		runBlocking {
			val sections = mutableListOf<HomeSectionType>()
			loadHomeSections(
				sections = listOf(HomeSectionType.LIBRARY_BUTTONS, HomeSectionType.RESUME),
				includeRecentlyReleased = false,
				loader = { throw ApiClientException("offline") },
			) { section, _ -> sections.add(section) }.shouldBeNull()
			sections shouldBe listOf(HomeSectionType.RESUME)
		}
	}

	test("cancelling library discovery stops section publication") {
		val sections = mutableListOf<HomeSectionType>()
		shouldThrow<CancellationException> {
			runBlocking {
				loadHomeSections(
					sections = listOf(HomeSectionType.LIBRARY_BUTTONS, HomeSectionType.RESUME),
					includeRecentlyReleased = false,
					loader = { throw CancellationException() },
				) { section, _ -> sections.add(section) }
			}
		}
		sections.shouldBeEmpty()
	}

	test("a failed User Views request does not abort independent Home rows") {
		 runBlocking {
			loadHomeUserViews(required = true) {
				throw ApiClientException("User Views unavailable")
			}.shouldBeNull()
		}
	}

	test("User Views are not requested when no configured row needs them") {
		runBlocking {
			var requested = false
			val views = loadHomeUserViews(required = false) {
				requested = true
				emptyList()
			}

			views.orEmpty().shouldBeEmpty()
			requested shouldBe false
		}
	}

	test("User Views cancellation is not treated as a server failure") {
		shouldThrow<CancellationException> {
			runBlocking {
				loadHomeUserViews(required = true) {
					throw CancellationException("cancelled")
				}
			}
		}
	}

	test("unexpected User Views failures are not hidden as server failures") {
		shouldThrow<IllegalStateException> {
			runBlocking {
				loadHomeUserViews(required = true) {
					error("mapping failed")
				}
			}
		}
	}
})
