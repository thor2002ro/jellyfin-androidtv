package org.jellyfin.androidtv.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.MediaType

interface UserViewsRepository {
	val views: Flow<Collection<BaseItemDto>>

	fun withSpecialViews(views: Collection<BaseItemDto>): List<BaseItemDto>
	fun isSupported(collectionType: CollectionType?): Boolean
	fun allowViewSelection(collectionType: CollectionType?): Boolean
	fun allowGridView(collectionType: CollectionType?): Boolean
}

class UserViewsRepositoryImpl(
	private val api: ApiClient,
	private val context: Context,
	private val userRepository: UserRepository,
) : UserViewsRepository {
	override val views = flow {
		val views by api.userViewsApi.getUserViews()
		val filteredViews = withSpecialViews(views.items)
		emit(filteredViews)
	}.flowOn(Dispatchers.IO)

	override fun withSpecialViews(views: Collection<BaseItemDto>): List<BaseItemDto> = buildList {
		val supportedViews = views.filter { isSupported(it.collectionType) }
		addAll(supportedViews)

		val hasLiveTvView = supportedViews.any { it.collectionType == CollectionType.LIVETV }
		val canAccessLiveTv = userRepository.currentUser.value?.policy?.enableLiveTvAccess == true
		if (!hasLiveTvView && canAccessLiveTv) add(createLiveTvView())
	}

	override fun isSupported(collectionType: CollectionType?) = collectionType !in unsupportedCollectionTypes
	override fun allowViewSelection(collectionType: CollectionType?) = collectionType !in disallowViewSelectionCollectionTypes
	override fun allowGridView(collectionType: CollectionType?) = collectionType !in disallowGridViewCollectionTypes

	private fun createLiveTvView() = BaseItemDto(
		id = LiveTvOption.LIVE_TV_VIEW_ID,
		type = BaseItemKind.COLLECTION_FOLDER,
		mediaType = MediaType.UNKNOWN,
		collectionType = CollectionType.LIVETV,
		name = context.getString(R.string.pref_live_tv_cat),
		displayPreferencesId = LiveTvOption.LIVE_TV_DISPLAY_PREFERENCES_ID,
	)

	private companion object {
		private val unsupportedCollectionTypes = arrayOf(
			CollectionType.BOOKS,
			CollectionType.FOLDERS
		)

		private val disallowViewSelectionCollectionTypes = arrayOf(
			CollectionType.LIVETV,
			CollectionType.MUSIC,
			CollectionType.PHOTOS,
		)

		private val disallowGridViewCollectionTypes = arrayOf(
			CollectionType.LIVETV,
			CollectionType.MUSIC
		)
	}
}
