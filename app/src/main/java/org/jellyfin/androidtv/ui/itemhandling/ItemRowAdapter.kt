package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.ChapterItemInfo
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.data.querying.GetAdditionalPartsRequest
import org.jellyfin.androidtv.data.querying.GetSeriesTimersRequest
import org.jellyfin.androidtv.data.querying.GetSpecialsRequest
import org.jellyfin.androidtv.data.querying.GetTrailersRequest
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.BrowseGridFragment
import org.jellyfin.androidtv.ui.browsing.EnhancedBrowseFragment
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.TextItemPresenter
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetAlbumArtistsRequest
import org.jellyfin.sdk.model.api.request.GetArtistsRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.api.request.GetSeasonsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import org.jellyfin.sdk.model.api.request.GetUpcomingEpisodesRequest
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.time.Instant

class ItemRowAdapter : MutableObjectAdapter<Any>, KoinComponent {
	private var query: GetItemsRequest? = null
	private var nextUpQuery: GetNextUpRequest? = null
	private var seasonQuery: GetSeasonsRequest? = null
	private var upcomingQuery: GetUpcomingEpisodesRequest? = null
	private var similarQuery: GetSimilarItemsRequest? = null
	private var specialsQuery: GetSpecialsRequest? = null
	private var additionalPartsQuery: GetAdditionalPartsRequest? = null
	private var trailersQuery: GetTrailersRequest? = null
	private var tvChannelQuery: GetLiveTvChannelsRequest? = null
	private var tvProgramQuery: GetRecommendedProgramsRequest? = null
	private var tvProgramSelectAction = BaseRowItemSelectAction.ShowDetails
	private var tvRecordingQuery: GetRecordingsRequest? = null
	private var artistsQuery: GetArtistsRequest? = null
	private var albumArtistsQuery: GetAlbumArtistsRequest? = null
	private var latestQuery: GetLatestMediaRequest? = null
	private var resumeQuery: GetResumeItemsRequest? = null

	var queryType: QueryType = QueryType.Items
		private set

	var sortBy: ItemSortBy? = null
		private set

	var sortOrder: SortOrder? = null
		private set

	private var filters: FilterOptions? = null
	private var retrieveFinishedListener: EmptyResponse? = null
	private var retrieveFinishedRunnable: Runnable? = null
	private var reRetrieveTriggers: Array<ChangeTriggerType>? = emptyArray()
	private var lastFullRetrieve: Instant? = null

	private var persons: Array<BaseItemPerson>? = null
	private var chapters: List<ChapterItemInfo>? = null
	private var items: List<BaseItemDto>? = null
	private var parent: MutableObjectAdapter<Row>? = null
	private var row: ListRow? = null
	private var siblingRow: Row? = null
	private var rowOrder = Double.MAX_VALUE
	private var chunkSize = 0

	var itemsLoaded = 0
		set(value) {
			field = value
			fullyLoaded = chunkSize == 0 || value >= totalItems
		}

	var totalItems = 0
	var preferParentThumb = false
		private set
	var isStaticHeight = false
		private set

	private var initialSelectedPosition = -1
	private var initialSelectedPositionApplied = false
	private var fullyLoaded = false
	private val currentlyRetrievingSemaphore = Any()
	private var currentlyRetrieving = false
	private lateinit var context: Context

	private val api by inject<ApiClient>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val userRepository by inject<UserRepository>()
	private val userViewsRepository by inject<UserViewsRepository>()

	constructor(
		context: Context,
		query: GetItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : this(context, query, chunkSize, preferParentThumb, false, presenter, parent)

	constructor(
		context: Context,
		query: GetItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		staticHeight: Boolean,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
		queryType: QueryType,
	) : super(presenter) {
		initialize(context, parent)
		this.query = query
		this.chunkSize = chunkSize
		this.preferParentThumb = preferParentThumb
		this.isStaticHeight = staticHeight
		this.queryType = queryType
	}

	constructor(
		context: Context,
		query: GetItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		staticHeight: Boolean,
		presenter: PresenterSelector,
		parent: MutableObjectAdapter<Row>?,
		queryType: QueryType,
	) : super(presenter) {
		initialize(context, parent)
		this.query = query
		this.chunkSize = chunkSize
		this.preferParentThumb = preferParentThumb
		this.isStaticHeight = staticHeight
		this.queryType = queryType
	}

	constructor(
		context: Context,
		query: GetItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		staticHeight: Boolean,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : this(context, query, chunkSize, preferParentThumb, staticHeight, presenter, parent, QueryType.Items)

	constructor(
		context: Context,
		query: GetArtistsRequest,
		chunkSize: Int,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		artistsQuery = query
		isStaticHeight = true
		this.chunkSize = chunkSize
		queryType = QueryType.Artists
	}

	constructor(
		context: Context,
		query: GetAlbumArtistsRequest,
		chunkSize: Int,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		albumArtistsQuery = query
		isStaticHeight = true
		this.chunkSize = chunkSize
		queryType = QueryType.AlbumArtists
	}

	constructor(
		context: Context,
		query: GetNextUpRequest,
		preferParentThumb: Boolean,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		nextUpQuery = query
		queryType = QueryType.NextUp
		this.preferParentThumb = preferParentThumb
		isStaticHeight = true
	}

	constructor(
		context: Context,
		@Suppress("UNUSED_PARAMETER") query: GetSeriesTimersRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		queryType = QueryType.SeriesTimer
	}

	constructor(
		context: Context,
		query: GetLatestMediaRequest,
		preferParentThumb: Boolean,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		latestQuery = query
		queryType = QueryType.LatestItems
		this.preferParentThumb = preferParentThumb
		isStaticHeight = true
	}

	constructor(
		people: List<BaseItemPerson>,
		context: Context,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		persons = people.toTypedArray()
		isStaticHeight = true
		queryType = QueryType.StaticPeople
	}

	constructor(
		context: Context,
		chapters: List<ChapterItemInfo>,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		this.chapters = chapters
		isStaticHeight = true
		queryType = QueryType.StaticChapters
	}

	constructor(
		context: Context,
		items: List<BaseItemDto>,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
		queryType: QueryType,
	) : super(presenter) {
		initialize(context, parent)
		this.items = items
		this.queryType = queryType
	}

	constructor(
		context: Context,
		items: List<BaseItemDto>,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
		@Suppress("UNUSED_PARAMETER") staticItems: Boolean,
	) : super(presenter) {
		initialize(context, parent)
		this.items = items
		queryType = QueryType.StaticItems
	}

	constructor(
		context: Context,
		query: GetSpecialsRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		specialsQuery = query
		queryType = QueryType.Specials
	}

	constructor(
		context: Context,
		query: GetAdditionalPartsRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		additionalPartsQuery = query
		queryType = QueryType.AdditionalParts
	}

	constructor(
		context: Context,
		query: GetTrailersRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		trailersQuery = query
		queryType = QueryType.Trailers
	}

	constructor(
		context: Context,
		query: GetLiveTvChannelsRequest,
		chunkSize: Int,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		tvChannelQuery = query
		this.chunkSize = chunkSize
		queryType = QueryType.LiveTvChannel
	}

	constructor(
		context: Context,
		query: GetRecommendedProgramsRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : this(context, query, presenter, parent, BaseRowItemSelectAction.ShowDetails)

	constructor(
		context: Context,
		query: GetRecommendedProgramsRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
		selectAction: BaseRowItemSelectAction,
	) : super(presenter) {
		initialize(context, parent)
		tvProgramQuery = query
		tvProgramSelectAction = selectAction
		queryType = QueryType.LiveTvProgram
		isStaticHeight = true
	}

	constructor(
		context: Context,
		query: GetRecordingsRequest,
		chunkSize: Int,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		tvRecordingQuery = query
		this.chunkSize = chunkSize
		queryType = QueryType.LiveTvRecording
		isStaticHeight = true
	}

	constructor(
		context: Context,
		query: GetSimilarItemsRequest,
		queryType: QueryType,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		similarQuery = query
		this.queryType = queryType
	}

	constructor(
		context: Context,
		query: GetUpcomingEpisodesRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		upcomingQuery = query
		queryType = QueryType.Upcoming
	}

	constructor(
		context: Context,
		query: GetSeasonsRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		seasonQuery = query
		queryType = QueryType.Season
	}

	constructor(
		context: Context,
		@Suppress("UNUSED_PARAMETER") query: GetUserViewsRequest,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		queryType = QueryType.Views
		isStaticHeight = true
	}

	constructor(
		context: Context,
		query: GetResumeItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		staticHeight: Boolean,
		presenter: Presenter,
		parent: MutableObjectAdapter<Row>?,
	) : super(presenter) {
		initialize(context, parent)
		resumeQuery = query
		this.chunkSize = chunkSize
		this.preferParentThumb = preferParentThumb
		this.isStaticHeight = staticHeight
		queryType = QueryType.Resume
	}

	private fun initialize(context: Context, parent: MutableObjectAdapter<Row>?) {
		this.context = context
		this.parent = parent
	}

	private fun isCurrentlyRetrieving() = synchronized(currentlyRetrievingSemaphore) {
		currentlyRetrieving
	}

	private fun setCurrentlyRetrieving(currentlyRetrieving: Boolean) {
		synchronized(currentlyRetrievingSemaphore) {
			this.currentlyRetrieving = currentlyRetrieving
		}
	}

	@JvmOverloads
	fun setRow(row: ListRow?, rowOrder: Double = Double.MAX_VALUE) {
		this.row = row
		this.rowOrder = rowOrder
	}

	fun setSiblingRow(row: Row?) {
		siblingRow = row
	}

	fun setInitialSelectedPosition(position: Int) {
		initialSelectedPosition = position
		initialSelectedPositionApplied = false
	}

	fun getPendingInitialSelectedPosition(): Int? =
		initialSelectedPosition.takeIf { !initialSelectedPositionApplied && it >= 0 }

	fun markInitialSelectedPositionApplied() {
		initialSelectedPositionApplied = true
	}

	fun setReRetrieveTriggers(reRetrieveTriggers: Array<ChangeTriggerType>?) {
		this.reRetrieveTriggers = reRetrieveTriggers
	}

	fun setSortBy(option: BrowseGridFragment.SortOption) {
		if (option.value != sortBy || option.order != sortOrder) {
			sortBy = option.value
			sortOrder = option.order
			when (queryType) {
				QueryType.Artists -> artistsQuery = setArtistsSorting(requireNotNull(artistsQuery), option)
				QueryType.AlbumArtists -> albumArtistsQuery = setAlbumArtistsSorting(requireNotNull(albumArtistsQuery), option)
				else -> query = setItemsSorting(requireNotNull(query), option)
			}
			if (option.value != ItemSortBy.SORT_NAME) setStartLetter(null)
		}
	}

	fun getFilters(): FilterOptions? = filters

	fun setFilters(filters: FilterOptions) {
		this.filters = filters
		when (queryType) {
			QueryType.Artists -> artistsQuery = setArtistsFilter(requireNotNull(artistsQuery), filters.filters)
			QueryType.AlbumArtists -> albumArtistsQuery = setAlbumArtistsFilter(requireNotNull(albumArtistsQuery), filters.filters)
			else -> query = setItemsFilter(requireNotNull(query), filters.filters)
		}
		removeRow()
	}

	fun getStartLetter(): String? = when (queryType) {
		QueryType.Artists -> artistsQuery?.nameStartsWith
		QueryType.AlbumArtists -> albumArtistsQuery?.nameStartsWith
		else -> query?.nameStartsWith
	}

	fun setStartLetter(value: String?) {
		when (queryType) {
			QueryType.Artists -> artistsQuery = setArtistsStartLetter(requireNotNull(artistsQuery), value.takeUnless { it == "#" })
			QueryType.AlbumArtists -> albumArtistsQuery = setAlbumArtistsStartLetter(requireNotNull(albumArtistsQuery), value.takeUnless { it == "#" })
			else -> query = setItemsStartLetter(requireNotNull(query), value.takeUnless { it == "#" })
		}
	}

	fun removeRow() {
		val currentParent = parent
		if (currentParent == null) {
			clear()
			return
		}

		siblingRow?.let { currentParent.remove(it) }

		if (currentParent.size() == 1) {
			val emptyRow = ArrayObjectAdapter(TextItemPresenter()).apply {
				add(context.getString(R.string.lbl_no_items))
			}
			currentParent.add(ListRow(HeaderItem(context.getString(R.string.lbl_empty)), emptyRow))
		}

		row?.let { currentParent.remove(it) }
	}

	fun addRowToParentIfResultsReceived() {
		val currentRow = row ?: return
		val currentParent = parent ?: return
		if (itemsLoaded <= 0) return

		removeEmptyPlaceholder(currentParent)
		if (currentParent.indexOf(currentRow) != -1) return

		val insertIndex = (0 until currentParent.size())
			.firstOrNull { index ->
				val adapter = (currentParent.get(index) as? ListRow)?.adapter as? ItemRowAdapter
				adapter != null && adapter.rowOrder > rowOrder
			}
			?: currentParent.size()

		currentParent.add(insertIndex, currentRow)
	}

	private fun removeEmptyPlaceholder(parent: MutableObjectAdapter<Row>) {
		val emptyIndex = (0 until parent.size())
			.firstOrNull { index ->
				val header = (parent.get(index) as? ListRow)?.headerItem
				header?.name == context.getString(R.string.lbl_empty)
			}
			?: return

		parent.removeAt(emptyIndex)
	}

	fun loadMoreItemsIfNeeded(pos: Int) {
		if (fullyLoaded) return
		if (isCurrentlyRetrieving()) {
			Timber.d("Not loading more because currently retrieving")
			return
		}

		if (chunkSize > 0) {
			if (pos >= itemsLoaded - (chunkSize / 1.7)) {
				Timber.d(
					"Loading more items trigger pos <%s> itemsLoaded <%s> from total <%s> with chunkSize <%s>",
					pos,
					itemsLoaded,
					totalItems,
					chunkSize
				)
				retrieveNext()
			}
		} else if (pos >= itemsLoaded - 20) {
			Timber.d(
				"Loading more items trigger pos <%s> itemsLoaded <%s> from total <%s>",
				pos,
				itemsLoaded,
				totalItems
			)
			retrieveNext()
		}
	}

	private fun retrieveNext() {
		if (fullyLoaded || isCurrentlyRetrieving() || chunkSize == 0) return

		when (queryType) {
			QueryType.LiveTvChannel -> {
				val request = tvChannelQuery ?: return
				notifyRetrieveStarted()
				retrieveLiveTvChannels(api, request, itemsLoaded, chunkSize)
			}

			QueryType.Artists -> {
				val request = artistsQuery ?: return
				notifyRetrieveStarted()
				retrieveArtists(api, request, itemsLoaded, chunkSize)
			}

			QueryType.AlbumArtists -> {
				val request = albumArtistsQuery ?: return
				notifyRetrieveStarted()
				retrieveAlbumArtists(api, request, itemsLoaded, chunkSize)
			}

			else -> {
				val request = query ?: return
				notifyRetrieveStarted()
				retrieveItems(api, request, itemsLoaded, chunkSize)
			}
		}
	}

	@Suppress("FunctionName")
	fun ReRetrieveIfNeeded(): Boolean {
		val triggers = reRetrieveTriggers ?: return false
		val lastRetrieve = lastFullRetrieve ?: return false

		var retrieve = false
		for (trigger in triggers) {
			retrieve = when (trigger) {
				ChangeTriggerType.LibraryUpdated -> retrieve || dataRefreshService.lastLibraryChange?.let(lastRetrieve::isBefore) == true
				ChangeTriggerType.MoviePlayback -> retrieve || dataRefreshService.lastMoviePlayback?.let(lastRetrieve::isBefore) == true
				ChangeTriggerType.TvPlayback -> retrieve || dataRefreshService.lastTvPlayback?.let(lastRetrieve::isBefore) == true
				ChangeTriggerType.MusicPlayback -> retrieve || dataRefreshService.lastPlayback?.let(lastRetrieve::isBefore) == true
				ChangeTriggerType.FavoriteUpdate -> retrieve || dataRefreshService.lastFavoriteUpdate?.let(lastRetrieve::isBefore) == true
			}
		}

		if (retrieve) {
			Timber.d("Re-retrieving row of type %s", queryType.toString())
			Retrieve()
		}

		return retrieve
	}

	@Suppress("FunctionName")
	fun Retrieve() {
		notifyRetrieveStarted()
		lastFullRetrieve = Instant.now()
		itemsLoaded = 0

		when (queryType) {
			QueryType.Items -> {
				val request = requireNotNull(query)
				val startIndex = request.startIndex
				val limit = request.limit
				if (startIndex != null && limit != null) {
					retrieveItems(api, request, startIndex, limit)
				} else {
					retrieveItems(api, request, 0, chunkSize)
				}
			}

			QueryType.NextUp -> retrieveNextUpItems(api, requireNotNull(nextUpQuery))
			QueryType.LatestItems -> retrieveLatestMedia(api, requireNotNull(latestQuery))
			QueryType.Upcoming -> retrieveUpcomingEpisodes(api, requireNotNull(upcomingQuery))
			QueryType.Season -> retrieveSeasons(api, requireNotNull(seasonQuery))
			QueryType.Views -> retrieveUserViews(api, userViewsRepository)
			QueryType.SimilarSeries,
			QueryType.SimilarMovies -> retrieveSimilarItems(api, requireNotNull(similarQuery))

			QueryType.LiveTvChannel -> retrieveLiveTvChannels(api, requireNotNull(tvChannelQuery), 0, chunkSize)
			QueryType.LiveTvProgram -> retrieveLiveTvRecommendedPrograms(api, requireNotNull(tvProgramQuery), tvProgramSelectAction)
			QueryType.LiveTvRecording -> retrieveLiveTvRecordings(api, requireNotNull(tvRecordingQuery))
			QueryType.StaticPeople -> loadPeople()
			QueryType.StaticChapters -> loadChapters()
			QueryType.StaticItems -> loadStaticItems()
			QueryType.Specials -> retrieveSpecialFeatures(api, requireNotNull(specialsQuery))
			QueryType.AdditionalParts -> retrieveAdditionalParts(api, requireNotNull(additionalPartsQuery))
			QueryType.Trailers -> retrieveTrailers(api, requireNotNull(trailersQuery))
			QueryType.Search -> {
				loadStaticItems()
				addToParentIfResultsReceived()
			}

			QueryType.Artists -> retrieveArtists(api, requireNotNull(artistsQuery), 0, chunkSize)
			QueryType.AlbumArtists -> retrieveAlbumArtists(api, requireNotNull(albumArtistsQuery), 0, chunkSize)
			QueryType.AudioPlaylists -> retrieveAudioPlaylists()
			QueryType.Premieres -> retrievePremieres(api, requireNotNull(query))
			QueryType.SeriesTimer -> retrieveLiveTvSeriesTimers(
				api = api,
				context = context,
				canManageRecordings = Utils.canManageRecordings(userRepository.currentUser.value)
			)

			QueryType.Resume -> retrieveResumeItems(api, requireNotNull(resumeQuery))
		}
	}

	private fun loadPeople() {
		val currentPersons = persons
		if (currentPersons != null) {
			currentPersons.forEach { person -> add(BaseItemPersonBaseRowItem(person)) }
		} else {
			removeRow()
		}

		notifyRetrieveFinished()
	}

	private fun loadChapters() {
		val currentChapters = chapters
		if (currentChapters != null) {
			currentChapters.forEach { chapter -> add(ChapterItemInfoBaseRowItem(chapter)) }
		} else {
			removeRow()
		}

		notifyRetrieveFinished()
	}

	private fun loadStaticItems() {
		val currentItems = items
		if (currentItems != null) {
			currentItems.forEach { item -> add(BaseItemDtoBaseRowItem(item)) }
			itemsLoaded = currentItems.size
		} else {
			removeRow()
		}

		notifyRetrieveFinished()
	}

	private fun addToParentIfResultsReceived() {
		addRowToParentIfResultsReceived()
	}

	private fun retrieveAudioPlaylists() {
		clear()
		add(GridButtonBaseRowItem(GridButton(EnhancedBrowseFragment.FAVSONGS, context.getString(R.string.lbl_favorites), R.drawable.favorites)))
		itemsLoaded = 1
		retrieveItems(api, requireNotNull(query), 0, chunkSize)
	}

	@JvmOverloads
	fun notifyRetrieveFinished(exception: Exception? = null) {
		if (exception != null) Timber.w(exception, "Failed to retrieve items")

		setCurrentlyRetrieving(false)
		if (retrieveFinishedListener != null) {
			if (exception == null) retrieveFinishedListener?.onResponse()
			else retrieveFinishedListener?.onError(exception)
		}
		if (exception == null) retrieveFinishedRunnable?.run()
	}

	fun setRetrieveFinishedListener(response: EmptyResponse?) {
		retrieveFinishedListener = response
	}

	fun setRetrieveFinishedListener(response: Runnable?) {
		retrieveFinishedRunnable = response
	}

	private fun notifyRetrieveStarted() {
		setCurrentlyRetrieving(true)
	}
}
