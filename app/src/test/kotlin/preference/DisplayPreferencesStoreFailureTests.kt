package org.jellyfin.androidtv.preference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.operations.DisplayPreferenceApi
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.ScrollDirection
import org.jellyfin.sdk.model.api.SortOrder

class DisplayPreferencesStoreFailureTests : FunSpec({
	test("a failed initial load cannot be committed as an empty preference document") {
		runBlocking {
			val api = mockk<ApiClient>()
			val displayPreferenceApi = mockk<DisplayPreferenceApi>()
			every { api.getOrCreateApi(DisplayPreferenceApi::class, any()) } returns displayPreferenceApi
			coEvery { displayPreferenceApi.getDisplayPreferences(any(), any(), any()) } throws ApiClientException("load failed")
			coEvery { displayPreferenceApi.updateDisplayPreferences(any(), any(), any(), any()) } returns Response(Unit, 204, emptyMap())
			val preferences = UserSettingPreferences(api)

			preferences.update() shouldBe false
			preferences[UserSettingPreferences.seriesThumbnailsEnabled] = false

			preferences.commit() shouldBe false
		}
	}

	test("a failed user change load exposes defaults instead of the previous user's values") {
		runBlocking {
			val api = mockk<ApiClient>()
			val displayPreferenceApi = mockk<DisplayPreferenceApi>()
			every { api.getOrCreateApi(DisplayPreferenceApi::class, any()) } returns displayPreferenceApi
			var requestCount = 0
			coEvery { displayPreferenceApi.getDisplayPreferences(any(), any(), any()) } coAnswers {
				requestCount++
				when (requestCount) {
					1, 2 -> Response(displayPreferences(seriesThumbnailsEnabled = false), 200, emptyMap())
					else -> throw ApiClientException("new user load failed")
				}
			}
			val liveTvPreferences = LiveTvPreferences(api)
			val userSettingPreferences = UserSettingPreferences(api)
			val repository = PreferencesRepository(api, liveTvPreferences, userSettingPreferences)

			userSettingPreferences.update() shouldBe true
			userSettingPreferences[UserSettingPreferences.seriesThumbnailsEnabled] shouldBe false

			repository.onSessionChanged()

			userSettingPreferences[UserSettingPreferences.seriesThumbnailsEnabled] shouldBe true
			userSettingPreferences.shouldUpdate shouldBe true
		}
	}

	test("server preference documents are never saved concurrently") {
		runBlocking {
			val api = mockk<ApiClient>()
			val displayPreferenceApi = mockk<DisplayPreferenceApi>()
			every { api.getOrCreateApi(DisplayPreferenceApi::class, any()) } returns displayPreferenceApi
			coEvery { displayPreferenceApi.getDisplayPreferences(any(), any(), any()) } returns
				Response(displayPreferences(seriesThumbnailsEnabled = true), 200, emptyMap())
			val firstSaveStarted = CompletableDeferred<Unit>()
			val releaseFirstSave = CompletableDeferred<Unit>()
			var activeSaves = 0
			var maximumActiveSaves = 0
			var saveCount = 0
			coEvery { displayPreferenceApi.updateDisplayPreferences(any(), any(), any(), any()) } coAnswers {
				saveCount++
				activeSaves++
				maximumActiveSaves = maxOf(maximumActiveSaves, activeSaves)
				try {
					if (saveCount == 1) {
						firstSaveStarted.complete(Unit)
						releaseFirstSave.await()
					}
					Response(Unit, 204, emptyMap())
				} finally {
					activeSaves--
				}
			}
			val preferences = UserSettingPreferences(api)
			preferences.update() shouldBe true

			coroutineScope {
				val firstSave = async { preferences.commit() }
				firstSaveStarted.await()
				val secondSave = async { preferences.commit() }
				yield()
				releaseFirstSave.complete(Unit)

				firstSave.await() shouldBe true
				secondSave.await() shouldBe true
			}

			maximumActiveSaves shouldBe 1
		}
	}
})

private fun displayPreferences(seriesThumbnailsEnabled: Boolean) = DisplayPreferencesDto(
	rememberIndexing = false,
	primaryImageHeight = 0,
	primaryImageWidth = 0,
	customPrefs = mapOf("pref_enable_series_thumbnails" to seriesThumbnailsEnabled.toString()),
	scrollDirection = ScrollDirection.HORIZONTAL,
	showBackdrop = false,
	rememberSorting = false,
	sortOrder = SortOrder.ASCENDING,
	showSidebar = false,
)
