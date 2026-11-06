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
