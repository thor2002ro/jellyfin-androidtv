package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.querying.GetSeriesTimersRequest
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

class BrowseViewFragment : EnhancedBrowseFragment() {
	override fun setupQueries(rowLoader: RowLoader) {
		when (mFolder?.collectionType ?: CollectionType.UNKNOWN) {
			CollectionType.MOVIES -> {
				itemType = BaseItemKind.MOVIE

				mRows.add(BrowseRowDef(
					getString(R.string.lbl_continue_watching),
					BrowsingUtils.createResumeItemsRequest(mFolder.id, BaseItemKind.MOVIE),
					0,
					arrayOf(ChangeTriggerType.MoviePlayback),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_latest),
					BrowsingUtils.createLatestMediaRequest(mFolder.id),
					arrayOf(ChangeTriggerType.MoviePlayback, ChangeTriggerType.LibraryUpdated),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_favorites),
					BrowsingUtils.createFavoriteItemsRequest(mFolder.id, BaseItemKind.MOVIE),
					60,
					arrayOf(ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_collections),
					BrowsingUtils.createCollectionsRequest(mFolder.id),
					60,
					arrayOf(ChangeTriggerType.LibraryUpdated),
				))

				rowLoader.loadRows(mRows)
			}

			CollectionType.TVSHOWS -> {
				itemType = BaseItemKind.SERIES

				mRows.add(BrowseRowDef(
					getString(R.string.lbl_continue_watching),
					BrowsingUtils.createResumeItemsRequest(mFolder.id, BaseItemKind.EPISODE),
					0,
					arrayOf(ChangeTriggerType.TvPlayback),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_next_up),
					BrowsingUtils.createGetNextUpRequest(mFolder.id),
					arrayOf(ChangeTriggerType.TvPlayback),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_latest),
					BrowsingUtils.createLatestMediaRequest(mFolder.id, BaseItemKind.EPISODE, true),
					arrayOf(ChangeTriggerType.LibraryUpdated),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_favorites),
					BrowsingUtils.createFavoriteItemsRequest(mFolder.id, BaseItemKind.SERIES),
					60,
					arrayOf(ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate),
				))

				rowLoader.loadRows(mRows)
			}

			CollectionType.MUSIC -> {
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_latest),
					BrowsingUtils.createLatestMediaRequest(mFolder.id, BaseItemKind.AUDIO, true),
					arrayOf(ChangeTriggerType.LibraryUpdated),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_last_played),
					BrowsingUtils.createLastPlayedRequest(mFolder.id),
					0,
					false,
					true,
					arrayOf(ChangeTriggerType.MusicPlayback, ChangeTriggerType.LibraryUpdated),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_favorites),
					BrowsingUtils.createFavoriteItemsRequest(mFolder.id, BaseItemKind.MUSIC_ALBUM),
					60,
					false,
					true,
					arrayOf(ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate),
				))
				mRows.add(BrowseRowDef(
					getString(R.string.lbl_playlists),
					BrowsingUtils.createPlaylistsRequest(),
					60,
					false,
					true,
					arrayOf(ChangeTriggerType.LibraryUpdated),
					QueryType.AudioPlaylists,
				))

				rowLoader.loadRows(mRows)
			}

			else -> {
				if (requireArguments().getBoolean(Extras.IsLiveTvSeriesRecordings, false)) {
					mRows.add(BrowseRowDef(getString(R.string.lbl_series_recordings), GetSeriesTimersRequest))
					rowLoader.loadRows(mRows)
				}
			}
		}
	}
}
