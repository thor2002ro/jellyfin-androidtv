package org.jellyfin.androidtv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class SearchViewModelTests : FunSpec({
	test("submitting a new pending query never retains rows from the previous query") {
		coroutineScope {
			mockkStatic("androidx.lifecycle.ViewModelKt")
			every { any<ViewModel>().viewModelScope } returns CoroutineScope(coroutineContext)
			try {
				val firstMovie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)
				val secondSeries = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SERIES)
				val repository = object : SearchRepository {
					override suspend fun search(searchTerm: String, itemTypes: Collection<BaseItemKind>): Result<List<BaseItemDto>> = when {
						searchTerm == "first" -> Result.success(listOf(firstMovie))
						itemTypes == setOf(BaseItemKind.SERIES) -> Result.success(listOf(secondSeries))
						else -> Result.failure(IllegalStateException("Disconnected"))
					}
				}
				val viewModel = SearchViewModel(repository)
				viewModel.searchImmediately("first")
				repeat(20) { yield() }
				viewModel.searchResultsFlow.value.single { it.labelRes == R.string.lbl_movies }.items shouldBe listOf(firstMovie)
				viewModel.searchDebounced("second")
				viewModel.searchImmediately("second")
				repeat(20) { yield() }
				viewModel.searchResultsFlow.value.single { it.labelRes == R.string.lbl_movies }.items shouldBe emptyList()
				viewModel.searchResultsFlow.value.single { it.labelRes == R.string.lbl_series }.items shouldBe listOf(secondSeries)
				viewModel.searchResultsFlow.value.any { firstMovie in it.items } shouldBe false
				viewModel.hasSearchErrorFlow.value shouldBe true
			} finally {
				coroutineContext.cancelChildren()
				unmockkStatic("androidx.lifecycle.ViewModelKt")
			}
		}
	}

	test("submitting a failed query again retries and keeps successful categories visible") {
		coroutineScope {
			mockkStatic("androidx.lifecycle.ViewModelKt")
			every { any<ViewModel>().viewModelScope } returns CoroutineScope(coroutineContext)
			try {
				val movie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)
				val retryResults = CompletableDeferred<Unit>()
				var fail = true
				var requests = 0
				val repository = object : SearchRepository {
					override suspend fun search(searchTerm: String, itemTypes: Collection<BaseItemKind>): Result<List<BaseItemDto>> {
						requests++
						if (!fail) retryResults.await()
						return if (fail && itemTypes != setOf(BaseItemKind.MOVIE)) {
							Result.failure(IllegalStateException("Disconnected"))
						} else {
							Result.success(listOf(movie))
						}
					}
				}
				val viewModel = SearchViewModel(repository)
				viewModel.searchImmediately(" movie ") shouldBe true
				repeat(20) { yield() }
				viewModel.searchResultsFlow.value.single { it.labelRes == R.string.lbl_movies }.items shouldBe listOf(movie)
				viewModel.hasSearchErrorFlow.value shouldBe true
				val initialRequests = requests
				fail = false
				viewModel.searchImmediately("movie") shouldBe true
				repeat(20) { yield() }
				viewModel.hasSearchErrorFlow.value shouldBe false
				viewModel.searchResultsFlow.value.single { it.labelRes == R.string.lbl_movies }.items shouldBe listOf(movie)
				retryResults.complete(Unit)
				repeat(20) { yield() }
				requests shouldBe initialRequests * 2
				viewModel.searchResultsFlow.value.all { it.items == listOf(movie) } shouldBe true
				viewModel.hasSearchErrorFlow.value shouldBe false
			} finally {
				coroutineContext.cancelChildren()
				unmockkStatic("androidx.lifecycle.ViewModelKt")
			}
		}
	}

	test("failed searches are distinct from successful empty results and clearing resets the error") {
		coroutineScope {
			mockkStatic("androidx.lifecycle.ViewModelKt")
			every { any<ViewModel>().viewModelScope } returns CoroutineScope(coroutineContext)
			try {
				var fail = true
				val repository = object : SearchRepository {
					override suspend fun search(searchTerm: String, itemTypes: Collection<BaseItemKind>): Result<List<BaseItemDto>> =
						if (fail) Result.failure(IllegalStateException("Disconnected")) else Result.success(emptyList())
				}
				val viewModel = SearchViewModel(repository)
				viewModel.searchImmediately("movie")
				repeat(20) { yield() }
				viewModel.hasSearchErrorFlow.value shouldBe true
				viewModel.searchImmediately(" ")
				viewModel.hasSearchErrorFlow.value shouldBe false
				viewModel.searchResultsFlow.value shouldBe emptyList()
				fail = false
				viewModel.searchImmediately("movie")
				repeat(20) { yield() }
				viewModel.hasSearchErrorFlow.value shouldBe false
				viewModel.searchResultsFlow.value.all { it.items.isEmpty() } shouldBe true
			} finally {
				coroutineContext.cancelChildren()
				unmockkStatic("androidx.lifecycle.ViewModelKt")
			}
		}
	}

	test("submitting a pending debounced query starts immediately with at most three concurrent requests") {
		coroutineScope {
			mockkStatic("androidx.lifecycle.ViewModelKt")
			every { any<ViewModel>().viewModelScope } returns CoroutineScope(coroutineContext)
			try {
				val releaseResults = CompletableDeferred<Unit>()
				var requests = 0
				var activeRequests = 0
				var maxActiveRequests = 0
				val repository = object : SearchRepository {
					override suspend fun search(searchTerm: String, itemTypes: Collection<BaseItemKind>): Result<List<BaseItemDto>> {
						requests++
						activeRequests++
						maxActiveRequests = maxOf(maxActiveRequests, activeRequests)
						try {
							releaseResults.await()
							return Result.success(emptyList())
						} finally {
							activeRequests--
						}
					}
				}
				val viewModel = SearchViewModel(repository)
				viewModel.searchDebounced("movie") shouldBe true
				repeat(20) { yield() }
				requests shouldBe 0
				viewModel.searchImmediately("movie") shouldBe true
				repeat(20) { yield() }
				requests shouldBe 3
				activeRequests shouldBe 3
				releaseResults.complete(Unit)
				repeat(20) { yield() }
				maxActiveRequests shouldBe 3
				requests shouldBe viewModel.searchResultsFlow.value.size
				viewModel.searchDebounced(" movie ") shouldBe false
			} finally {
				coroutineContext.cancelChildren()
				unmockkStatic("androidx.lifecycle.ViewModelKt")
			}
		}
	}

	test("search publishes completed categories before slow categories and drops cancelled query results") {
		coroutineScope {
			mockkStatic("androidx.lifecycle.ViewModelKt")
			every { any<ViewModel>().viewModelScope } returns CoroutineScope(coroutineContext)
			try {
				val slowResults = CompletableDeferred<Unit>()
				val movie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)
				val repository = object : SearchRepository {
					override suspend fun search(searchTerm: String, itemTypes: Collection<BaseItemKind>): Result<List<BaseItemDto>> {
						if (itemTypes != setOf(BaseItemKind.MOVIE)) slowResults.await()
						return Result.success(listOf(movie))
					}
				}
				val viewModel = SearchViewModel(repository)
				viewModel.searchImmediately("first")
				repeat(20) { yield() }
				viewModel.searchResultsFlow.value.single { it.labelRes == R.string.lbl_movies }.items shouldBe listOf(movie)
				viewModel.searchImmediately("")
				slowResults.complete(Unit)
				repeat(20) { yield() }
				viewModel.searchResultsFlow.value shouldBe emptyList()
			} finally {
				coroutineContext.cancelChildren()
				unmockkStatic("androidx.lifecycle.ViewModelKt")
			}
		}
	}
})
