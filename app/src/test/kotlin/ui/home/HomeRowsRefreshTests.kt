package org.jellyfin.androidtv.ui.home

import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class HomeRowsRefreshTests : FunSpec({
	test("batched user changes retain every item and cannot downgrade a scheduled full refresh") {
		coroutineScope {
			mockkStatic("androidx.lifecycle.LifecycleOwnerKt")
			val ownerScope = mockk<LifecycleCoroutineScope> {
				every { coroutineContext } returns this@coroutineScope.coroutineContext
			}
			every { any<LifecycleOwner>().lifecycleScope } returns ownerScope
			try {
				val fragment = HomeRowsFragment()
				HomeRowsFragment::class.java.getDeclaredMethod("scheduleHomeRowsRefresh", Long::class.javaPrimitiveType)
					.apply { isAccessible = true }.invoke(fragment, 3_500L)
				val refresh = HomeRowsFragment::class.java.getDeclaredMethod("refreshHomeRows", Set::class.java)
					.apply { isAccessible = true }
				val first = UUID.randomUUID()
				val second = UUID.randomUUID()
				refresh.invoke(fragment, setOf(first))
				refresh.invoke(fragment, setOf(second))
				HomeRowsFragment::class.java.getDeclaredField("pendingFullRefresh").apply { isAccessible = true }
					.get(fragment) shouldBe true
				HomeRowsFragment::class.java.getDeclaredField("pendingUserDataItems").apply { isAccessible = true }
					.get(fragment) shouldBe setOf(first, second)
			} finally {
				coroutineContext.cancelChildren()
				unmockkStatic("androidx.lifecycle.LifecycleOwnerKt")
			}
		}
	}

	test("user data refreshes membership rows and affected media but not unrelated libraries") {
		val changedId = UUID.randomUUID()
		fun row(type: QueryType, id: UUID = UUID.randomUUID()) = mockk<ItemRowAdapter> {
			every { queryType } returns type
			every { iterator() } answers { listOf(BaseItemDtoBaseRowItem(BaseItemDto(id = id, type = BaseItemKind.MOVIE))).iterator() }
		}
		row(QueryType.NextUp).shouldRefreshForUserData(setOf(changedId)) shouldBe true
		row(QueryType.Resume).shouldRefreshForUserData(setOf(changedId)) shouldBe true
		row(QueryType.LatestItems, changedId).shouldRefreshForUserData(setOf(changedId)) shouldBe true
		row(QueryType.Items, changedId).shouldRefreshForUserData(setOf(changedId)) shouldBe true
		row(QueryType.LatestItems).shouldRefreshForUserData(setOf(changedId)) shouldBe false
		row(QueryType.Views, changedId).shouldRefreshForUserData(setOf(changedId)) shouldBe false
	}

	test("a failed User Views load requests one Home rebuild on the next refresh") {
		val fragment = HomeRowsFragment()
		val messages = mockk<CustomMessageRepository>(relaxed = true)
		HomeRowsFragment::class.java.getDeclaredField("customMessageRepository\$delegate").apply { isAccessible = true }
			.set(fragment, lazyOf(messages))
		val failed = HomeRowsFragment::class.java.getDeclaredField("homeUserViewsFailed").apply { isAccessible = true }
		failed.set(fragment, true)
		HomeRowsFragment::class.java.getDeclaredMethod("retrieveHomeRows", Set::class.java).apply { isAccessible = true }
			.invoke(fragment, emptySet<UUID>())
		verify(exactly = 1) { messages.pushMessage(CustomMessage.RefreshHomeConfiguration) }
		failed.get(fragment) shouldBe false
	}
})
