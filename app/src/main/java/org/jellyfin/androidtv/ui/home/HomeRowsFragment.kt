package org.jellyfin.androidtv.ui.home

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.livetv.LiveTvCardActionHandler
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.ui.presentation.recreatePresentation
import org.jellyfin.androidtv.ui.shared.transition.ProfileLoginAnimation
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<Lazy<PlaybackManager>>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()
	private val playbackHelper by inject<PlaybackHelper>()

	private val helper by lazy {
		HomeFragmentHelper(
			context = requireContext(),
			userRepository = userRepository,
			itemLimit = userSettingPreferences[UserSettingPreferences.homeRowItemLimit],
			includeNextUpRewatching = userSettingPreferences[UserSettingPreferences.homeNextUpRewatching],
		)
	}

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true
	private var homeRowsLoaded = false
	private var homeUserViewsFailed = false
	private var rowsDensityDpi = 0
	private var rowsFontScale = 0f
	private val refreshRowMeasurements = Runnable {
		val grid = verticalGridView ?: return@Runnable
		val config = resources.configuration
		if (rowsDensityDpi != config.densityDpi || rowsFontScale != config.fontScale) {
			rowsDensityDpi = config.densityDpi
			rowsFontScale = config.fontScale
			grid.recreatePresentation()
		}
	}
	private var backgroundUpdateJob: Job? = null
	private var homeRowsDebounceJob: Job? = null
	private var homeRowsRefreshJob: Job? = null
	private var pendingFullRefresh = false
	private val pendingUserDataItems = mutableSetOf<UUID>()
	private var currentBackgroundItemId: String? = null
	private val homeRowAdapters = linkedSetOf<ItemRowAdapter>()
	private val liveTvActions by lazy {
		LiveTvCardActionHandler(this, api, playbackHelper) { _, _ ->
			refreshHomeRows()
		}
	}

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		adapter = MutableObjectAdapter<Row>(PositionableListRowPresenter())

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					CustomMessage.RefreshHomeNextUp -> homeRowAdapters.toList()
						.filter { it.nextUpQueryProvider != null }
						.forEach(ItemRowAdapter::Retrieve)
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { message ->
						val data = message.data
						if (data != null && data.userId == userRepository.currentUser.value?.id) {
							refreshHomeRows(data.userDataList.mapTo(mutableSetOf()) { it.itemId })
						}
					}
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach { refreshHomeRows() }
					.launchIn(this)
			}
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		rowsDensityDpi = resources.configuration.densityDpi
		rowsFontScale = resources.configuration.fontScale
		if (!homeRowsLoaded) {
			viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
				val currentUser = withTimeout(30.seconds) {
					userRepository.currentUser.filterNotNull().first()
				}

				if (!isActive) return@launch
				val layout = createHomeSectionLayout(
					sections = userSettingPreferences.activeHomesections,
					combineContinueWatchingAndNextUp = userSettingPreferences[UserSettingPreferences.homeCombineContinueWatchingNextUp],
				)
				val liveTvEnabled = currentUser.policy?.enableLiveTvAccess == true
				val includeRecentlyReleased = userSettingPreferences[UserSettingPreferences.homeRecentlyReleased]
				val cardPresenter = withContext(Dispatchers.Main) {
					val rowsAdapter = adapter as MutableObjectAdapter<Row>
					// A destroyed view may have left an incomplete initial load.
					rowsAdapter.clear()
					homeRowAdapters.clear()
					val cardPresenter = createHomeCardPresenter(userSettingPreferences[UserSettingPreferences.homeWideCards])
					notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
					nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
					(activity as? MainActivity)?.profileLoginAnimation?.let { animation ->
						verticalGridView?.prepareProfileTransition(animation)
					}
					cardPresenter
				}

				val userViews = loadHomeSections(
					sections = layout.sections,
					includeRecentlyReleased = includeRecentlyReleased,
					loader = { userViewsRepository.views.first() },
				) { section, views ->
					withContext(Dispatchers.Main) {
						val rows = createHomeRows(section, views, liveTvEnabled, layout.combineContinueWatchingAndNextUp)
						rows.forEachIndexed { index, row ->
							val order = layout.sections.indexOf(section) + index.toDouble() / (rows.size + 1)
							row.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>, order)
						}
						startHomeRowUpdates()
					}
				}
				withContext(Dispatchers.Main) {
					homeUserViewsFailed = userViews == null
					if (includeRecentlyReleased && userViews != null) {
						helper.loadRecentlyReleased(userViews).addToRowsAdapter(
							requireContext(), cardPresenter,
							adapter as MutableObjectAdapter<Row>, layout.sections.size.toDouble(),
						)
					}
					homeRowsLoaded = true
					startHomeRowUpdates()
				}
			}
		} else {
			startHomeRowUpdates()
		}
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		// Keep the loaded rows and their update jobs; only native view measurements are stale.
		verticalGridView?.let { grid ->
			grid.removeCallbacks(refreshRowMeasurements)
			grid.post(refreshRowMeasurements)
		}
	}

	private fun createHomeRows(
		section: HomeSectionType,
		userViews: Collection<BaseItemDto>,
		liveTvEnabled: Boolean,
		combineContinueWatchingAndNextUp: Boolean,
	): List<HomeFragmentRow> = buildList {
		when (section) {
			HomeSectionType.LATEST_MEDIA -> add(helper.loadRecentlyAdded(userViews))
			HomeSectionType.LIBRARY_TILES_SMALL -> add(HomeFragmentViewsRow(small = false, initialViews = userViews))
			HomeSectionType.LIBRARY_BUTTONS -> add(HomeFragmentViewsRow(small = true, initialViews = userViews))
			HomeSectionType.RESUME -> add(helper.loadResumeVideo(combineContinueWatchingAndNextUp))
			HomeSectionType.RESUME_AUDIO -> add(helper.loadResumeAudio())
			HomeSectionType.RESUME_BOOK -> Unit // Kept for compatibility with values stored by other Jellyfin clients.
			HomeSectionType.ACTIVE_RECORDINGS -> if (liveTvEnabled) add(helper.loadLatestLiveTvRecordings())
			HomeSectionType.NEXT_UP -> add(helper.loadNextUp())
			HomeSectionType.LIVE_TV -> if (liveTvEnabled) {
				add(HomeFragmentLiveTVRow(requireActivity(), userRepository))
				add(helper.loadOnNow(liveTvActions::onLongClick))
			}
			HomeSectionType.NONE -> Unit
		}
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		// Returning from Android Home can leave the Leanback view at 0x0 inside
		// an already-sized Compose container. Request a native layout after resume
		// so the retained rows are measured again without recreating or reloading them.
		view?.post {
			view?.let { rowsView ->
				rowsView.requestLayout()
				rowsView.doOnLayout {
					Timber.i("Home rows layout after resume: %dx%d rows=%d", it.width, it.height, adapter.size())
				}
			}
		}

		//React to deletion
		val deletedItem = currentItem
		if (currentRow != null && deletedItem != null && deletedItem.baseItem?.id == dataRefreshService.lastDeletedItemId) {
			(currentRow!!.adapter as ItemRowAdapter).remove(deletedItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		if (!justLoaded) {
			refreshCurrentItem()
			scheduleHomeRowsRefresh()
		} else {
			justLoaded = false
		}

		// Update audio queue
		Timber.d("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	override fun onPause() {
		homeRowsDebounceJob?.cancel()
		homeRowsRefreshJob?.cancel()
		liveTvActions.dismiss()
		super.onPause()
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.d("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	private fun refreshHomeRows(changedItemIds: Set<UUID>? = null) {
		if (changedItemIds == null) pendingFullRefresh = true
		else pendingUserDataItems.addAll(changedItemIds)
		homeRowsRefreshJob?.cancel()
		homeRowsDebounceJob?.cancel()
		homeRowsDebounceJob = lifecycleScope.launch {
			delay(HOME_ROWS_REFRESH_DEBOUNCE_MS)
			val changedItems = if (pendingFullRefresh) null else pendingUserDataItems.toSet()
			retrieveHomeRows(changedItems)
		}
	}

	private fun retrieveHomeRows(changedItemIds: Set<UUID>? = null) {
		pendingFullRefresh = false
		pendingUserDataItems.clear()
		if (homeUserViewsFailed) {
			homeUserViewsFailed = false
			customMessageRepository.pushMessage(CustomMessage.RefreshHomeConfiguration)
			return
		}
		startHomeRowUpdates()
		homeRowAdapters.toList()
			.filter { changedItemIds == null || it.shouldRefreshForUserData(changedItemIds) }
			.forEach(ItemRowAdapter::Retrieve)
	}

	private fun trackHomeRows() {
		repeat(adapter.size()) { i ->
			val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
			if (rowAdapter != null) {
				homeRowAdapters.add(rowAdapter)
			}
		}
	}

	private fun startHomeRowUpdates() {
		trackHomeRows()
		val owner = viewLifecycleOwner
		homeRowAdapters.forEach { it.startLiveTvProgramUpdates(owner) }
	}

	private fun scheduleHomeRowsRefresh(delayMs: Long = HOME_ROWS_REFRESH_DELAY_MS) {
		pendingFullRefresh = true
		homeRowsRefreshJob?.cancel()
		homeRowsRefreshJob = lifecycleScope.launch {
			delay(delayMs)
			retrieveHomeRows()
		}
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.d("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		backgroundUpdateJob?.cancel()
		homeRowsDebounceJob?.cancel()
		homeRowsRefreshJob?.cancel()
		mediaManager.removeAudioEventListener(this)
	}

	override fun onDestroyView() {
		verticalGridView?.removeCallbacks(refreshRowMeasurements)
		trackHomeRows()
		homeRowAdapters.forEach(ItemRowAdapter::cancelRetrieval)
		backgroundUpdateJob?.cancel()
		homeRowsDebounceJob?.cancel()
		homeRowsRefreshJob?.cancel()
		super.onDestroyView()
	}

	private fun updateBackground(item: BaseRowItem?) {
		backgroundUpdateJob?.cancel()

		val itemId = item?.baseItem?.id?.toString()
		if (itemId == currentBackgroundItemId) return

		backgroundUpdateJob = lifecycleScope.launch {
			delay(HOME_BACKGROUND_UPDATE_DELAY_MS)

			if (item?.baseItem == null) {
				currentBackgroundItemId = null
				backgroundService.clearBackgrounds()
				return@launch
			}

			currentBackgroundItemId = itemId
			backgroundService.setBackground(item.baseItem)
		}
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item is GridButton) {
				when (item.id) {
					LiveTvOption.LIVE_TV_CHANNELS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvChannels(getString(R.string.channels)))
					LiveTvOption.LIVE_TV_GUIDE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvGuide)
					LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSchedule)
					LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvRecordings)
					LiveTvOption.LIVE_TV_SERIES_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
				}
			}

			if (item !is BaseRowItem) return
			if (row !is ListRow) return
			@Suppress("UNCHECKED_CAST")
			itemLauncher.launch(item, row.adapter as MutableObjectAdapter<Any>, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item !is BaseRowItem) {
				currentItem = null
				updateBackground(null)
			} else {
				currentItem = item
				currentRow = row as ListRow

				val itemRowAdapter = row.adapter as? ItemRowAdapter
				itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))

				updateBackground(item)
			}
		}
	}
}

internal fun VerticalGridView.prepareProfileTransition(animation: ProfileLoginAnimation) {
	doOnPreDraw {
		val holder = getChildAt(0)?.let { getChildViewHolder(it) } as? ItemBridgeAdapter.ViewHolder
		val row = holder?.let {
			(it.presenter as? RowPresenter)?.getRowViewHolder(it.viewHolder)
		} as? ListRowPresenter.ViewHolder
		// Empty row headers can lay out before their cards arrive.
		if ((row?.gridView?.childCount ?: 0) > 0) {
			// Let this frame finish drawing the cards before revealing Home.
			post { animation.homeRowsReady = true }
		} else prepareProfileTransition(animation)
	}
}

internal fun ItemRowAdapter.shouldRefreshForUserData(changedItemIds: Set<UUID>): Boolean = when (queryType) {
	QueryType.NextUp, QueryType.Resume -> true
	QueryType.Views -> false
	else -> any { item ->
		val baseItem = (item as? BaseRowItem)?.baseItem
		baseItem?.id in changedItemIds || baseItem?.currentProgram?.id in changedItemIds
	}
}

private const val HOME_ROWS_REFRESH_DELAY_MS = 3_500L
private const val HOME_ROWS_REFRESH_DEBOUNCE_MS = 250L
private const val HOME_BACKGROUND_UPDATE_DELAY_MS = 1_500L

internal fun createHomeCardPresenter(useWideCards: Boolean) = when (useWideCards) {
	false -> CardPresenter()
	true -> CardPresenter(
		showInfo = true,
		imageType = ImageType.THUMB,
		staticHeight = 120,
		uniformAspect = false,
	)
}

private fun homeSectionUsesUserViews(section: HomeSectionType) = section in setOf(
	HomeSectionType.LATEST_MEDIA,
	HomeSectionType.LIBRARY_TILES_SMALL,
	HomeSectionType.LIBRARY_BUTTONS,
)

internal suspend fun loadHomeUserViews(
	required: Boolean,
	loader: suspend () -> Collection<BaseItemDto>,
): Collection<BaseItemDto>? {
	if (!required) return emptyList()
	return try {
		loader()
	} catch (err: ApiClientException) {
		Timber.w(err, "Unable to load User Views; skipping dependent Home rows")
		null
	}
}

internal suspend fun loadHomeSections(
	sections: Collection<HomeSectionType>,
	includeRecentlyReleased: Boolean,
	loader: suspend () -> Collection<BaseItemDto>,
	onSection: suspend (HomeSectionType, Collection<BaseItemDto>) -> Unit,
): Collection<BaseItemDto>? {
	for (section in sections.filterNot(::homeSectionUsesUserViews)) onSection(section, emptyList())
	val views = loadHomeUserViews(sections.any(::homeSectionUsesUserViews) || includeRecentlyReleased, loader)
	if (views != null) {
		for (section in sections.filter(::homeSectionUsesUserViews)) onSection(section, views)
	}
	return views
}
