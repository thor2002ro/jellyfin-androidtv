package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.exception.ApiClientException

class HomeUserViewsFailureTests : FunSpec({
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
