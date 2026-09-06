package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.querying.GetSeriesTimersRequest
import org.jellyfin.androidtv.data.querying.GetSpecialsRequest
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItemSelectAction
import org.jellyfin.sdk.model.api.request.GetAlbumArtistsRequest
import org.jellyfin.sdk.model.api.request.GetArtistsRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest

class BrowseRowDef private constructor(
	val headerText: String?,
	private val itemsQuery: GetItemsRequest? = null,
	private val nextUpItemsQuery: GetNextUpRequest? = null,
	private val similarItemsQuery: GetSimilarItemsRequest? = null,
	private val latestMediaQuery: GetLatestMediaRequest? = null,
	private val liveTvChannelQuery: GetLiveTvChannelsRequest? = null,
	private val liveTvProgramQuery: GetRecommendedProgramsRequest? = null,
	private val liveTvRecordingQuery: GetRecordingsRequest? = null,
	private val liveTvSeriesTimerQuery: GetSeriesTimersRequest? = null,
	private val artistsItemsQuery: GetArtistsRequest? = null,
	private val albumArtistsItemsQuery: GetAlbumArtistsRequest? = null,
	private val resumeItemsQuery: GetResumeItemsRequest? = null,
	private val specialFeaturesQuery: GetSpecialsRequest? = null,
	val queryType: QueryType,
	val chunkSize: Int = 0,
	val isStaticHeight: Boolean = false,
	val preferParentThumb: Boolean = false,
	val useChannelCards: Boolean = false,
	val liveTvProgramSelectAction: BaseRowItemSelectAction = BaseRowItemSelectAction.ShowDetails,
	val changeTriggers: Array<ChangeTriggerType>? = null,
) {
	val query: GetItemsRequest
		get() = requireNotNull(itemsQuery)

	val nextUpQuery: GetNextUpRequest
		get() = requireNotNull(nextUpItemsQuery)

	val similarQuery: GetSimilarItemsRequest
		get() = requireNotNull(similarItemsQuery)

	val latestItemsQuery: GetLatestMediaRequest
		get() = requireNotNull(latestMediaQuery)

	val tvChannelQuery: GetLiveTvChannelsRequest
		get() = requireNotNull(liveTvChannelQuery)

	val programQuery: GetRecommendedProgramsRequest
		get() = requireNotNull(liveTvProgramQuery)

	val recordingQuery: GetRecordingsRequest
		get() = requireNotNull(liveTvRecordingQuery)

	val seriesTimerQuery: GetSeriesTimersRequest
		get() = requireNotNull(liveTvSeriesTimerQuery)

	val artistsQuery: GetArtistsRequest
		get() = requireNotNull(artistsItemsQuery)

	val albumArtistsQuery: GetAlbumArtistsRequest
		get() = requireNotNull(albumArtistsItemsQuery)

	val resumeQuery: GetResumeItemsRequest
		get() = requireNotNull(resumeItemsQuery)

	val specialsQuery: GetSpecialsRequest
		get() = requireNotNull(specialFeaturesQuery)

	constructor(
		header: String?,
		query: GetItemsRequest,
		chunkSize: Int,
	) : this(header, query, chunkSize, false, false)

	constructor(
		header: String?,
		query: GetItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		staticHeight: Boolean,
	) : this(
		headerText = header,
		itemsQuery = query,
		queryType = QueryType.Items,
		chunkSize = chunkSize,
		isStaticHeight = staticHeight,
		preferParentThumb = preferParentThumb,
	)

	constructor(
		header: String?,
		query: GetItemsRequest,
		chunkSize: Int,
		changeTriggers: Array<ChangeTriggerType>,
	) : this(header, query, chunkSize, false, false, changeTriggers)

	constructor(
		header: String?,
		query: GetItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		staticHeight: Boolean,
		changeTriggers: Array<ChangeTriggerType>,
	) : this(header, query, chunkSize, preferParentThumb, staticHeight, changeTriggers, QueryType.Items)

	constructor(
		header: String?,
		query: GetItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		staticHeight: Boolean,
		changeTriggers: Array<ChangeTriggerType>,
		queryType: QueryType,
	) : this(
		headerText = header,
		itemsQuery = query,
		queryType = queryType,
		chunkSize = chunkSize,
		isStaticHeight = staticHeight,
		preferParentThumb = preferParentThumb,
		changeTriggers = changeTriggers,
	)

	constructor(
		header: String?,
		query: GetArtistsRequest,
		chunkSize: Int,
		changeTriggers: Array<ChangeTriggerType>,
	) : this(
		headerText = header,
		artistsItemsQuery = query,
		queryType = QueryType.Artists,
		chunkSize = chunkSize,
		changeTriggers = changeTriggers,
	)

	constructor(
		header: String?,
		query: GetAlbumArtistsRequest,
		chunkSize: Int,
		changeTriggers: Array<ChangeTriggerType>,
	) : this(
		headerText = header,
		albumArtistsItemsQuery = query,
		queryType = QueryType.AlbumArtists,
		chunkSize = chunkSize,
		changeTriggers = changeTriggers,
	)

	constructor(
		header: String?,
		query: GetSeriesTimersRequest,
	) : this(
		headerText = header,
		liveTvSeriesTimerQuery = query,
		queryType = QueryType.SeriesTimer,
		isStaticHeight = true,
	)

	constructor(
		header: String?,
		query: GetNextUpRequest,
		changeTriggers: Array<ChangeTriggerType>,
	) : this(
		headerText = header,
		nextUpItemsQuery = query,
		queryType = QueryType.NextUp,
		isStaticHeight = true,
		changeTriggers = changeTriggers,
	)

	constructor(
		header: String?,
		query: GetLatestMediaRequest,
		changeTriggers: Array<ChangeTriggerType>,
	) : this(
		headerText = header,
		latestMediaQuery = query,
		queryType = QueryType.LatestItems,
		isStaticHeight = true,
		changeTriggers = changeTriggers,
	)

	constructor(
		header: String?,
		query: GetLiveTvChannelsRequest,
	) : this(
		headerText = header,
		liveTvChannelQuery = query,
		queryType = QueryType.LiveTvChannel,
	)

	constructor(
		header: String?,
		query: GetLiveTvChannelsRequest,
		changeTriggers: Array<ChangeTriggerType>,
	) : this(
		headerText = header,
		liveTvChannelQuery = query,
		queryType = QueryType.LiveTvChannel,
		changeTriggers = changeTriggers,
	)

	constructor(
		header: String?,
		query: GetRecommendedProgramsRequest,
	) : this(header, query, null, false)

	constructor(
		header: String?,
		query: GetRecommendedProgramsRequest,
		useChannelCards: Boolean,
	) : this(header, query, null, useChannelCards)

	constructor(
		header: String?,
		query: GetRecommendedProgramsRequest,
		useChannelCards: Boolean,
		selectAction: BaseRowItemSelectAction,
	) : this(header, query, null, useChannelCards, selectAction)

	constructor(
		header: String?,
		query: GetRecommendedProgramsRequest,
		changeTriggers: Array<ChangeTriggerType>?,
	) : this(header, query, changeTriggers, false)

	constructor(
		header: String?,
		query: GetRecommendedProgramsRequest,
		changeTriggers: Array<ChangeTriggerType>?,
		useChannelCards: Boolean,
	) : this(header, query, changeTriggers, useChannelCards, BaseRowItemSelectAction.ShowDetails)

	constructor(
		header: String?,
		query: GetRecommendedProgramsRequest,
		changeTriggers: Array<ChangeTriggerType>?,
		useChannelCards: Boolean,
		selectAction: BaseRowItemSelectAction,
	) : this(
		headerText = header,
		liveTvProgramQuery = query,
		queryType = QueryType.LiveTvProgram,
		changeTriggers = changeTriggers,
		useChannelCards = useChannelCards,
		liveTvProgramSelectAction = selectAction,
	)

	constructor(
		header: String?,
		query: GetRecordingsRequest,
	) : this(header, query, 0)

	constructor(
		header: String?,
		query: GetRecordingsRequest,
		chunkSize: Int,
	) : this(
		headerText = header,
		liveTvRecordingQuery = query,
		queryType = QueryType.LiveTvRecording,
		chunkSize = chunkSize,
	)

	constructor(
		header: String?,
		query: GetSimilarItemsRequest,
		type: QueryType,
	) : this(
		headerText = header,
		similarItemsQuery = query,
		queryType = type,
	)

	constructor(
		header: String?,
		query: GetResumeItemsRequest,
		chunkSize: Int,
		preferParentThumb: Boolean,
		staticHeight: Boolean,
		changeTriggers: Array<ChangeTriggerType>,
	) : this(
		headerText = header,
		resumeItemsQuery = query,
		queryType = QueryType.Resume,
		chunkSize = chunkSize,
		isStaticHeight = staticHeight,
		preferParentThumb = preferParentThumb,
		changeTriggers = changeTriggers,
	)

	constructor(
		header: String?,
		query: GetSpecialsRequest,
	) : this(
		headerText = header,
		specialFeaturesQuery = query,
		queryType = QueryType.Specials,
	)
}
