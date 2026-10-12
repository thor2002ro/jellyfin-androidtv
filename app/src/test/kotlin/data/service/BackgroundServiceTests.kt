package org.jellyfin.androidtv.data.service

import android.content.Context
import coil3.ImageLoader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BackdropBehavior
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class BackgroundServiceTests : FunSpec({
	test("rapid backdrop selection only loads the final artwork") {
		coroutineScope {
			mockkStatic("kotlinx.coroutines.CoroutineScopeKt", "org.jellyfin.androidtv.util.apiclient.JellyfinImageKt")
			every { MainScope() } returns CoroutineScope(coroutineContext)
			every { any<JellyfinImage>().getUrl(any(), any(), any(), any(), any()) } answers { firstArg<JellyfinImage>().tag }
			val context = mockk<Context>(relaxed = true)
			every { context.applicationContext } returns context
			val preferences = mockk<UserPreferences>()
			every { preferences[UserPreferences.backdropBehavior] } returns BackdropBehavior.BACKDROP_WITHOUT_BLUR
			val requested = CopyOnWriteArrayList<String>()
			val loader = mockk<ImageLoader>()
			coEvery { loader.execute(any()) } answers {
				requested.add(firstArg<coil3.request.ImageRequest>().data.toString())
				mockk { every { image } returns null }
			}
			val service = BackgroundService(context, mockk(), mockk(), preferences, loader)
			try {
				service.setBackground(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, backdropImageTags = listOf("first")))
				delay(80)
				requested.toList() shouldBe emptyList()
				service.setBackground(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, backdropImageTags = listOf("second")))
				withTimeout(5_000) {
					while (requested.isEmpty()) delay(20)
				}
				requested.toList() shouldBe listOf("second")
			} finally {
				service.clearBackgrounds()
				coroutineContext.cancelChildren()
				unmockkStatic("kotlinx.coroutines.CoroutineScopeKt", "org.jellyfin.androidtv.util.apiclient.JellyfinImageKt")
			}
		}
	}
})
