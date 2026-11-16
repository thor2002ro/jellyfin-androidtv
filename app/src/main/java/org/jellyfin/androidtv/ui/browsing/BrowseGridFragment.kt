package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import androidx.core.view.isNotEmpty
import androidx.core.view.size
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import coil3.ImageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.constant.GridDirection
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.LibraryCardSpacing
import org.jellyfin.androidtv.constant.LibraryViewStyle
import org.jellyfin.androidtv.constant.PosterSize
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.data.model.FilterOptions.Companion.decode
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.databinding.HorizontalGridBrowseBinding
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.cardSpacing
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.filterFavoritesOnly
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.filterUnwatchedOnly
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.filters
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.gridDirection
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.imageType
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.posterSize
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.showCardTitles
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.sortBy
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.sortOrder
import org.jellyfin.androidtv.preference.LibraryPreferences.Companion.viewStyle
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.BrowseCardGridPresenter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.ComposeBrowseListPresenter
import org.jellyfin.androidtv.ui.presentation.ComposeVerticalGridPresenter
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter
import org.jellyfin.androidtv.ui.presentation.KaleidescapeStylePresenter
import org.jellyfin.androidtv.ui.presentation.configureBrowseBackground
import org.jellyfin.androidtv.ui.presentation.resolveBrowseGridSpacing
import org.jellyfin.androidtv.ui.presentation.shouldShowBrowseCardInfo
import org.jellyfin.androidtv.util.InfoLayoutHelper.addInfoRow
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.Utils.convertDpToPixel
import org.jellyfin.androidtv.util.readCustomMessagesOnLifecycle
import org.jellyfin.androidtv.util.runOnLifecycle
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDto.Companion.serializer
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber.Forest.d
import timber.log.Timber.Forest.e
import timber.log.Timber.Forest.v
import java.text.MessageFormat
import java.util.Objects
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class BrowseGridFragment : Fragment(), View.OnKeyListener {
	private var mainTitle: String? = null
	private var mTemporaryFilters: FilterOptions? = null
	private var mFilterChoices = LibraryFilterChoices()
	private var mActivity: FragmentActivity? = null
	private var mCurrentItem: BaseRowItem? = null
	private val mClickedListener = CompositeClickedListener()
	private val mSelectedListener = CompositeSelectedListener()
	private val mHandler = Handler(Looper.getMainLooper())
	private var mCardHeight = 0
	private var mRowDef: BrowseRowDef? = null
	private var mCardPresenter: CardPresenter? = null

	private var justLoaded = true
	private var mPosterSizeSetting = PosterSize.MED
	private var mImageType = ImageType.POSTER
	private var mGridDirection = GridDirection.HORIZONTAL
	private var mCardSpacing = LibraryCardSpacing.NORMAL
	private var mViewStyle = LibraryViewStyle.CARDS
	private var mShowCardTitles = false
	private var determiningPosterSize = false

	private lateinit var mParentId: UUID
	private lateinit var mFolder: BaseItemDto
	private lateinit var libraryPreferences: LibraryPreferences

	private var viewBinding: HorizontalGridBrowseBinding? = null
	private val binding get() = checkNotNull(viewBinding)
	private var mAdapter: ItemRowAdapter? = null
	private var mAlphabetPickerController: AlphabetPickerController? = null
	private var mGridPresenter: Presenter? = null
	private var mGridViewHolder: Presenter.ViewHolder? = null
	private var mGridView: View? = null
	private var mSelectedPosition = -1
	private var mGridHeight = -1
	private var mGridWidth = -1
	private var mGridItemSpacingHorizontal = 0
	private var mGridItemSpacingVertical = 0
	private var mGridPaddingLeft = 0
	private var mGridPaddingTop = 0
	private var mGridHeaderHeight = 0

	private val backgroundService = inject<BackgroundService>(BackgroundService::class.java)
	private val preferencesRepository =
		inject<PreferencesRepository>(PreferencesRepository::class.java)
	private val customMessageRepository =
		inject<CustomMessageRepository>(CustomMessageRepository::class.java)
	private val itemLauncher = inject<ItemLauncher>(ItemLauncher::class.java)
	private val keyProcessor = inject<KeyProcessor>(KeyProcessor::class.java)
	private val api = inject<ApiClient>(ApiClient::class.java)
	private val dataRefreshService = inject<DataRefreshService>(DataRefreshService::class.java)
	private val navigationRepository =
		inject<NavigationRepository>(NavigationRepository::class.java)
	private val imageLoader = inject<ImageLoader>(ImageLoader::class.java)

	private var mCardsScreenEst = 0
	private var mCardsScreenStride = 0
	private var mCardFocusScale =
		1.05 // 105%, just a default we use the resource card_scale_focus otherwise
	private val VIEW_SELECT_UPDATE_DELAY =
		250 // delay in ms until we update the top-row info for a selected item
	private val BACKGROUND_UPDATE_DELAY = 1500
	private val SELECTION_RESTORE_WINDOW_MS = 1500
	private var mDirty = true // CardHeight, RowDef or GridSize changed
	private var mPreferencesLoaded = false
	private var mQueryChangePending = false
	private var mOverlayFocusTarget: Int = OVERLAY_FOCUS_NONE
	private var mViewInitialized = false
	private var mPendingSelectedPosition = -1
	private var mRestoringSelectedPosition = false
	private var mSelectionRestoreGeneration = 0
	private val imagePrefetcher = BrowseImagePrefetcher()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// init with some working defaults
		val display = requireContext().getResources().getDisplayMetrics()
		// top + bottom in dp, elements scale with density so adjust accordingly
		mGridHeight =
			Math.round(display.heightPixels / getResources().getDisplayMetrics().density - 130.6f)
		mGridWidth = Math.round(display.widthPixels / getResources().getDisplayMetrics().density)


		mActivity = getActivity()

		val arguments = requireArguments()
		mFolder = Json.Default.decodeFromString<BaseItemDto>(
			serializer(), requireNotNull(arguments.getString(Extras.Folder))
		)
		mParentId = mFolder.id
		mainTitle = mFolder.name
		val presetFilters = arguments.getString(Extras.Filters)
		if (presetFilters != null) {
			mTemporaryFilters = decode(
				if (savedInstanceState != null)
					savedInstanceState.getString(Extras.Filters, presetFilters)
				else
					presetFilters
			)
		}
		mCardFocusScale = getResources().getFraction(R.fraction.card_scale_focus, 1, 1).toDouble()

		sortOptions = browseSortOptions(requireContext(), mFolder.collectionType)

		this.loadLibraryPreferences(
			preferencesRepository.value,
			requireNotNull(mFolder.displayPreferencesId)
		) { preferences ->
			initializeLibraryPreferences(preferences)
		}
	}

	private fun initializeLibraryPreferences(preferences: LibraryPreferences) {
		libraryPreferences = preferences
		mPosterSizeSetting = libraryPreferences.get<PosterSize>(posterSize)
		mImageType = libraryPreferences.get<ImageType>(imageType)
		mGridDirection = libraryPreferences.get<GridDirection>(gridDirection)
		mCardSpacing = libraryPreferences.get<LibraryCardSpacing>(cardSpacing)
		mViewStyle = libraryPreferences.get<LibraryViewStyle>(viewStyle)
		mShowCardTitles = libraryPreferences.get<Boolean>(showCardTitles)
		if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) {
			mImageType = ImageType.POSTER
			mShowCardTitles = false
		}

		setGridPresenter(createGridPresenter(mGridDirection, mViewStyle))

		setDefaultGridRowCols(mPosterSizeSetting, mImageType)
		setAutoCardGridValues()
		setupQueries()
		setupEventListeners()

		mPreferencesLoaded = true
		initializeView()
	}

	private val navigationControlsDirection: GridDirection
		get() = effectiveNavigationControlsDirection(
			mViewStyle,
			mGridDirection
		)

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		viewBinding = HorizontalGridBrowseBinding.inflate(inflater, container, false)
		configureBrowseBackground(binding.background, true)
		binding.counter.addOnLayoutChangeListener(View.OnLayoutChangeListener { counter: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ->
			configureFooterRotation(
				counter!!,
				GridDirection.VERTICAL == this.navigationControlsDirection,
				convertDpToPixel(requireContext(), 8)
			)
			updateVerticalStatusBounds()
		})
		binding.statusText.addOnLayoutChangeListener(View.OnLayoutChangeListener { status: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ->
			configureFooterRotation(
				status!!,
				GridDirection.VERTICAL == this.navigationControlsDirection,
				0
			)
		})
		binding.getRoot()
			.addOnLayoutChangeListener(View.OnLayoutChangeListener { root: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int -> updateVerticalStatusBounds() })
		return binding.getRoot()
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		if (mainTitle != null) binding.title.setText(mainTitle)
		initializeView()
	}

	private fun initializeView() {
		if (!mPreferencesLoaded || viewBinding == null || mViewInitialized) return

		configureNavigationControls()
		createGrid()
		loadGrid()
		addTools()

		mViewInitialized = true
	}

	private fun configureNavigationControls() {
		val controlsDirection = this.navigationControlsDirection
		val vertical = GridDirection.VERTICAL == controlsDirection
		configureBrowseBackground(
			binding.background,
			shouldDrawBrowseBackground(mViewStyle, mGridDirection)
		)
		val counterParams = binding.counter.getLayoutParams() as ViewGroup.MarginLayoutParams
		counterParams.bottomMargin = if (vertical) convertDpToPixel(requireContext(), 24) else 0
		binding.counter.setLayoutParams(counterParams)
		configureFooterRotation(binding.counter, vertical, convertDpToPixel(requireContext(), 8))
		configureFooterRotation(binding.statusText, vertical, 0)
		binding.toolBar.setVisibility(if (vertical) View.GONE else View.VISIBLE)
		binding.verticalToolBar.setVisibility(if (vertical) View.VISIBLE else View.GONE)
		binding.alphaPickerVertical.setVertical(true)
		binding.alphaPickerHorizontal.setVisibility(if (vertical) View.GONE else View.VISIBLE)
		binding.alphaPickerVertical.setVisibility(if (vertical) View.VISIBLE else View.GONE)
		mActiveToolBar = if (vertical) binding.verticalToolBar else binding.toolBar
		if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) {
			val statusParams = binding.statusText.getLayoutParams() as RelativeLayout.LayoutParams
			statusParams.removeRule(RelativeLayout.ALIGN_PARENT_END)
			statusParams.bottomMargin = 0
			binding.statusText.setMaxWidth(convertDpToPixel(requireContext(), 360))
		}
		KaleidescapeStylePresenter.configureHost(
			binding,
			mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE
		)
		updateNavigationControlsSpacing()
		val display = getResources().getDisplayMetrics()
		val gridParams = binding.rowsFragment.getLayoutParams() as ViewGroup.MarginLayoutParams
		var headerHeight = 0
		if (mViewStyle != LibraryViewStyle.KALEIDESCAPE_STYLE) {
			binding.title.measure(
				View.MeasureSpec.makeMeasureSpec(display.widthPixels, View.MeasureSpec.AT_MOST),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
			)
			val titleParams = binding.title.getLayoutParams() as ViewGroup.MarginLayoutParams
			val infoParams = binding.infoRow.getLayoutParams() as ViewGroup.MarginLayoutParams
			headerHeight = titleParams.topMargin + binding.title.getMeasuredHeight() +
					binding.genres.getLayoutParams().height + infoParams.topMargin + infoParams.height
		}
		mGridHeaderHeight = if (vertical) pixelsToDp(headerHeight, display.density) + 6 else 0
		mGridHeight =
			if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) Math.round(display.heightPixels / display.density - KaleidescapeStylePresenter.FOOTER_HEIGHT_DP) else pixelsToDp(
				display.heightPixels - headerHeight - convertDpToPixel(
					requireContext(),
					6
				) - gridParams.bottomMargin,
				display.density
			)
		setAutoCardGridValues()

		val picker =
			if (vertical) binding.alphaPickerVertical else binding.alphaPickerHorizontal
		picker.onAlphaSelected = { letter ->
			prepareForQueryChange()
			mAdapter!!.setStartLetter(letter.toString())
			loadGrid()
		}
	}

	private fun updateAlphabetPickerVisibility() {
		if (viewBinding == null) return
		binding.alphaPickerHorizontal.setVisibility(
			if (shouldShowAlphabetPicker(
					GridDirection.HORIZONTAL,
					this.navigationControlsDirection
				)
			) View.VISIBLE else View.GONE
		)
		binding.alphaPickerVertical.setVisibility(
			if (shouldShowAlphabetPicker(
					GridDirection.VERTICAL,
					this.navigationControlsDirection
				)
			) View.VISIBLE else View.GONE
		)
		if (mAdapter != null) {
			if (mAlphabetPickerController == null) mAlphabetPickerController =
				AlphabetPickerController(api.value, dataRefreshService.value)
			mAlphabetPickerController!!.update(
				getViewLifecycleOwner(), mAdapter!!, "#" + getString(
					R.string.byletter_letters
				),
				binding.alphaPickerHorizontal, binding.alphaPickerVertical
			)
		}
	}

	private fun updateNavigationControlsSpacing() {
		if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) return
		val controlsDirection = this.navigationControlsDirection
		val titleParams = binding.title.getLayoutParams() as ViewGroup.MarginLayoutParams
		titleParams.setMarginStart(
			if (GridDirection.HORIZONTAL == controlsDirection) convertDpToPixel(
				requireContext(),
				12
			) else Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 50f, resources.displayMetrics))
		)
		titleParams.topMargin = convertDpToPixel(
			requireContext(),
			if (GridDirection.HORIZONTAL == controlsDirection) 4 else 8
		)
		binding.title.setLayoutParams(titleParams)
		val headerPaddingStart =
			if (GridDirection.HORIZONTAL == controlsDirection) convertDpToPixel(
				requireContext(),
				8
			) else 0
		for (headerView in arrayOf<View>(binding.title, binding.genres, binding.infoRow)) {
			headerView.setPaddingRelative(
				headerPaddingStart,
				headerView.getPaddingTop(),
				headerView.getPaddingEnd(),
				headerView.getPaddingBottom()
			)
		}
		val infoParams = binding.infoRow.getLayoutParams() as ViewGroup.MarginLayoutParams
		infoParams.topMargin = convertDpToPixel(
			requireContext(),
			if (GridDirection.HORIZONTAL == controlsDirection) 4 else 2
		)
		binding.infoRow.setLayoutParams(infoParams)
		val toolbarParams = binding.toolBar.getLayoutParams() as ViewGroup.MarginLayoutParams
		toolbarParams.topMargin = convertDpToPixel(
			requireContext(),
			if (GridDirection.HORIZONTAL == controlsDirection) 14 else 12
		)
		binding.toolBar.setLayoutParams(toolbarParams)
		val verticalToolbarParams =
			binding.verticalToolBar.getLayoutParams() as ViewGroup.MarginLayoutParams
		verticalToolbarParams.width = convertDpToPixel(requireContext(), 26)
		verticalToolbarParams.setMarginEnd(0)
		binding.verticalToolBar.setLayoutParams(verticalToolbarParams)
		val verticalAlphabetParams = binding.alphaPickerVertical.getLayoutParams()
		verticalAlphabetParams.width = convertDpToPixel(requireContext(), 18)
		binding.alphaPickerVertical.setLayoutParams(verticalAlphabetParams)
		val counterParams = binding.counter.getLayoutParams() as ViewGroup.MarginLayoutParams
		counterParams.setMarginEnd(
			convertDpToPixel(
				requireContext(),
				if (GridDirection.VERTICAL == controlsDirection) 2 else 12
			)
		)
		binding.counter.setLayoutParams(counterParams)
		val alphabetParams = binding.alphaPickerHorizontal.getLayoutParams()
		alphabetParams.height = convertDpToPixel(requireContext(), 18)
		binding.alphaPickerHorizontal.setLayoutParams(alphabetParams)
		val statusParams = binding.statusText.getLayoutParams() as RelativeLayout.LayoutParams
		if (GridDirection.HORIZONTAL == controlsDirection) {
			statusParams.removeRule(RelativeLayout.ALIGN_BASELINE)
			statusParams.removeRule(RelativeLayout.ALIGN_PARENT_END)
			statusParams.addRule(RelativeLayout.ALIGN_START, binding.title.getId())
			statusParams.addRule(RelativeLayout.ABOVE, binding.alphaPickerHorizontal.getId())
			statusParams.setMarginEnd(0)
			statusParams.bottomMargin = 0
			binding.statusText.setMaxWidth(convertDpToPixel(requireContext(), 360))
		} else {
			statusParams.removeRule(RelativeLayout.ALIGN_BASELINE)
			statusParams.removeRule(RelativeLayout.ALIGN_START)
			statusParams.addRule(RelativeLayout.ALIGN_PARENT_END)
			statusParams.addRule(RelativeLayout.ABOVE, binding.counter.getId())
			statusParams.setMarginEnd(convertDpToPixel(requireContext(), 2))
		}
		binding.statusText.setLayoutParams(statusParams)
		val vertical = GridDirection.VERTICAL == controlsDirection
		val gridParams = binding.rowsFragment.getLayoutParams() as RelativeLayout.LayoutParams
		gridParams.setMarginStart(0)
		gridParams.setMarginEnd(0)
		if (vertical) {
			gridParams.removeRule(RelativeLayout.BELOW)
			gridParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
		} else {
			gridParams.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
			gridParams.addRule(RelativeLayout.BELOW, binding.infoRow.getId())
		}
		gridParams.topMargin = convertDpToPixel(requireContext(), if (vertical) 0 else 6)
		gridParams.bottomMargin =
			convertDpToPixel(requireContext(), getGridHostBottomMarginDp(controlsDirection))
		binding.rowsFragment.setLayoutParams(gridParams)
	}

	private fun updateVerticalStatusBounds() {
		if (viewBinding == null || GridDirection.VERTICAL != this.navigationControlsDirection) return
		val params = binding.statusText.getLayoutParams() as RelativeLayout.LayoutParams
		val gap = convertDpToPixel(requireContext(), 8)
		val bottomMargin =
			max(0, binding.counter.getWidth() - binding.counter.getHeight()) + gap
		if (params.bottomMargin != bottomMargin) {
			params.bottomMargin = bottomMargin
			binding.statusText.setLayoutParams(params)
		}
		val width: Int = getVerticalStatusWidth(
			binding.verticalToolBar.getBottom(),
			binding.counter.getBottom(),
			binding.counter.getWidth(),
			gap
		)
		if (binding.statusText.getMaxWidth() != width) binding.statusText.setMaxWidth(width)
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent): Boolean {
		if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE && keyCode == KeyEvent.KEYCODE_DPAD_UP) return false
		if (shouldMoveFocusFromGridToHome(
				this.navigationControlsDirection,
				event.getAction(),
				keyCode
			)
		) {
			return viewBinding != null && binding.clock.homeButton.requestFocus()
		}
		if (shouldMoveFocusFromGridToToolbar(
				this.navigationControlsDirection,
				event.getAction(),
				keyCode
			)
		) {
			return focusFirstToolbarAction()
		}
		if (event.getAction() != KeyEvent.ACTION_UP) return false
		return keyProcessor.value.handleKey(keyCode, mCurrentItem, mActivity)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		if (mTemporaryFilters != null) outState.putString(
			Extras.Filters,
			mTemporaryFilters!!.encode()
		)
	}

	override fun onDestroyView() {
		mHandler.removeCallbacksAndMessages(null)
		cancelSelectionRestore()
		imagePrefetcher.cancel()
		mAdapter?.cancelRetrieval()
		mAlphabetPickerController?.cancel()
		releaseGrid()
		super.onDestroyView()

		viewBinding = null
		mViewInitialized = false
	}

	private fun createGrid() {
		releaseGrid()
		mGridViewHolder = mGridPresenter!!.onCreateViewHolder(binding.rowsFragment)
		if (mGridViewHolder is ComposeBrowseListPresenter.ViewHolder) {
			val presenter = mGridPresenter as ComposeBrowseListPresenter
			mGridView = (mGridViewHolder as ComposeBrowseListPresenter.ViewHolder).listView
			presenter.configure(
				8 + getGridHostSideMarginDp(GridDirection.VERTICAL),
				8 + getGridHostEndMarginDp(GridDirection.VERTICAL), 12,
				resolveBrowseGridSpacing(6, mCardSpacing), mGridHeaderHeight
			)
		} else if (mGridPresenter is BrowseCardGridPresenter) {
			val presenter = mGridPresenter as BrowseCardGridPresenter
			val horizontal = presenter is HorizontalGridPresenter
			var paddingStart = mGridPaddingLeft
			var paddingEnd = mGridPaddingLeft
			if (!horizontal) {
				paddingStart += getGridHostSideMarginDp(GridDirection.VERTICAL)
				paddingEnd += getGridHostEndMarginDp(GridDirection.VERTICAL)
			}
			var verticalPadding = 16
			mGridView =
				if (horizontal) (mGridViewHolder as HorizontalGridPresenter.ViewHolder).gridView else mGridViewHolder!!.view
			if (horizontal) {
				val titleMargin = binding.title.getLayoutParams() as ViewGroup.MarginLayoutParams
				val clockMargin = binding.clock.getLayoutParams() as ViewGroup.MarginLayoutParams
				val density = getResources().getDisplayMetrics().density
				paddingStart = pixelsToDp(titleMargin.getMarginStart(), density)
				paddingEnd = pixelsToDp(clockMargin.getMarginEnd(), density)
				verticalPadding = mGridPaddingTop
			}
			if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) {
				paddingStart = 8
				paddingEnd = 8
			}
			presenter.configure(
				mImageType,
				mCardHeight,
				presenter.getSpanCount(),
				mGridItemSpacingHorizontal,
				mGridItemSpacingVertical,
				shouldShowBrowseCardInfo(mViewStyle, mShowCardTitles),
				paddingStart,
				paddingEnd,
				verticalPadding,
				mGridHeaderHeight
			)
		}
		if (mGridView!!.getId() == View.NO_ID) mGridView!!.setId(View.generateViewId())
		mGridView!!.setFocusable(true)
		mGridView!!.setOnFocusChangeListener(View.OnFocusChangeListener { view: View?, hasFocus: Boolean ->
			if (hasFocus) this.gridSelectedPosition =
				max(this.gridSelectedPosition, 0)
		})
		binding.rowsFragment.removeAllViews()
		binding.rowsFragment.addView(mGridViewHolder!!.view)

		updateAdapter()
	}

	private fun cancelSelectionRestore() {
		if (!mRestoringSelectedPosition) return

		mRestoringSelectedPosition = false
		mSelectionRestoreGeneration++
	}

	private var gridSelectedPosition: Int
		get() {
			if (mGridPresenter is BrowseCardGridPresenter) {
				return (mGridPresenter as BrowseCardGridPresenter).getPosition()
			} else if (mGridPresenter is ComposeBrowseListPresenter) {
				return (mGridPresenter as ComposeBrowseListPresenter).getPosition()
			}
			return -1
		}
		private set(position) {
			if (mGridPresenter is BrowseCardGridPresenter) {
				(mGridPresenter as BrowseCardGridPresenter).setPosition(position)
			} else if (mGridPresenter is ComposeBrowseListPresenter) {
				(mGridPresenter as ComposeBrowseListPresenter).setPosition(position)
			}
		}

	private fun keepGridFocused() {
		val grid = mGridView
		if (grid == null || (mAdapter?.itemsLoaded ?: 0) <= 0) return

		grid.post(Runnable {
			if (viewBinding == null || mGridView !== grid ||
				!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isHidden
			) return@Runnable
			val focused = mActivity?.currentFocus
			if (isFocusInsideGrid(focused, grid)) return@Runnable
			grid.requestFocus()
		})
	}

	private fun isFocusInsideGrid(focused: View?, grid: View?): Boolean {
		var view = focused
		while (view != null) {
			if (view === grid) return true
			val parent = view.getParent()
			view = if (parent is View) parent as View else null
		}
		return false
	}

	private fun applyPendingSelectedPosition() {
		if (mAdapter == null || mGridView == null || mAdapter!!.itemsLoaded <= 0) return

		val position: Int = getSafeSelectedPosition(
			mPendingSelectedPosition,
			mSelectedPosition,
			this.gridSelectedPosition,
			mAdapter!!.itemsLoaded
		)
		if (position < 0) return

		mSelectedPosition = position
		mPendingSelectedPosition = position
		mRestoringSelectedPosition = true
		val restoreGeneration = ++mSelectionRestoreGeneration
		this.gridSelectedPosition = position
		mHandler.postDelayed(Runnable {
			if (mSelectionRestoreGeneration == restoreGeneration) {
				mRestoringSelectedPosition = false
			}
		}, SELECTION_RESTORE_WINDOW_MS.toLong())
	}

	private fun prefetchCardImages(position: Int) {
		val adapter = mAdapter ?: return
		imagePrefetcher.prefetch(
			requireContext(), adapter, api.value, imageLoader.value, mFolder,
			mViewStyle, mImageType, mCardHeight, mCardsScreenEst, mCardsScreenStride, position
		)
	}

	private fun updateAdapter() {
		if (mGridView != null && mAdapter != null) {
			mGridPresenter!!.onBindViewHolder(mGridViewHolder!!, mAdapter)
			if (mSelectedPosition >= 0 && mAdapter!!.itemsLoaded > mSelectedPosition) {
				this.gridSelectedPosition = mSelectedPosition
			}
		}
	}

	/**
	 * Sets the grid presenter.
	 */
	private fun setGridPresenter(gridPresenter: Presenter) {
		requireNotNull(gridPresenter) { "Grid presenter may not be null" }
		require(!(gridPresenter !is BrowseCardGridPresenter && gridPresenter !is ComposeBrowseListPresenter)) { "Unsupported grid presenter" }

		releaseGrid()
		if (gridPresenter is ComposeBrowseListPresenter) {
			val presenter = gridPresenter
			presenter.setOnItemViewSelectedListener(mRowSelectedListener)
			presenter.setOnItemViewClickedListener(mClickedListener)
			presenter.setOnDirectionalKeyListener(Runnable { this.cancelSelectionRestore() })
			presenter.setOnKeyListener(this)
		} else if (gridPresenter is BrowseCardGridPresenter) {
			val presenter = gridPresenter
			presenter.setOnItemViewSelectedListener(mRowSelectedListener)
			presenter.setOnItemViewClickedListener(mClickedListener)
			presenter.setOnDirectionalKeyListener(Runnable { this.cancelSelectionRestore() })
			presenter.setOnKeyListener(this)
		}
		mGridPresenter = gridPresenter
	}

	private fun configureNavigationControlsFocus() {
		if (mGridView == null || mActiveToolBar == null) return

		val vertical = GridDirection.VERTICAL == this.navigationControlsDirection
		val picker =
			if (vertical) binding.alphaPickerVertical else binding.alphaPickerHorizontal
		picker.setGridFocusTargetId(mGridView!!.getId())
		if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) {
			KaleidescapeStylePresenter.configureFooterFocus(binding, mGridView!!)
			return
		}
		if (!shouldConfigureNavigationControlsFocus(this.navigationControlsDirection)) return

		val homeButton: View = binding.clock.homeButton
		val firstToolbarAction = mActiveToolBar!!.getChildAt(0)
		if (firstToolbarAction == null) return

		for (index in 0..<mActiveToolBar!!.size) {
			val action = mActiveToolBar!!.getChildAt(index)
			if (action.getId() == View.NO_ID) action.setId(View.generateViewId())
			action.setOnKeyListener(View.OnKeyListener { view: View?, keyCode: Int, event: KeyEvent? ->
				shouldMoveFocusFromToolbarToHome(
					this.navigationControlsDirection, event!!.getAction(), keyCode
				) &&
						homeButton.requestFocus()
			})
			action.setNextFocusDownId(mGridView!!.getId())
		}

		homeButton.setNextFocusDownId(firstToolbarAction.getId())
	}

	private fun focusFirstToolbarAction(): Boolean {
		return mActiveToolBar != null && mActiveToolBar!!.isNotEmpty() &&
				mActiveToolBar!!.getChildAt(0).requestFocus()
	}

	private fun releaseGrid() {
		releaseGrid(mGridPresenter, mGridViewHolder)
		mGridViewHolder = null
		mGridView = null
	}

	fun setItem(item: BaseRowItem?) {
		if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) return
		if (item != null) {
			binding.title.setText(item.getFullName(requireContext()))
			val baseItem = item.baseItem
			binding.genres.setText(
				if (baseItem != null && baseItem.genres != null) TextUtils.join(
					" / ",
					baseItem.genres!!
				) else ""
			)
			val badgeItem = if (item is BaseItemDtoBaseRowItem) item.streamBadgeItem else baseItem
			addInfoRow(requireContext(), badgeItem, binding.infoRow, true)
		} else {
			binding.title.setText("")
			binding.genres.setText("")
			binding.infoRow.removeAllViews()
		}
	}

	private lateinit var sortOptions: Map<Int, BrowseSortOption>

	private fun getSortOption(value: ItemSortBy?): BrowseSortOption {
		for (sortOption in sortOptions.values) {
			if (sortOption.value == value) return sortOption
		}

		return BrowseSortOption(
			getString(R.string.lbl_bracket_unknown),
			ItemSortBy.SORT_NAME,
			SortOrder.ASCENDING
		)
	}

	fun setFilterChoices(choices: LibraryFilterChoices) {
		mFilterChoices = choices
		setStatusText(mainTitle)
	}

	fun setStatusText(folderName: String?) {
		var text = getString(R.string.lbl_showing) + " "
		val filters = mAdapter!!.getFilters()
		if (filters == null || filters.isEmpty) {
			text += getString(R.string.lbl_all_items)
		} else {
			text += filters.summary(requireContext(), mFilterChoices)
		}

		if (mAdapter!!.getStartLetter() != null) {
			text += " " + getString(R.string.lbl_starting_with) + " " + mAdapter!!.getStartLetter()
		}

		text += " " + getString(R.string.lbl_from) + " '" + folderName + "' " + getString(R.string.lbl_sorted_by) + " " +
				getSortOption(mAdapter!!.sortBy).name + " " + mAdapter!!.sortOrder!!.menuArrow

		binding.statusText.setText(text)
	}

	private val mRowSelectedListener: OnItemViewSelectedListener =
		object : OnItemViewSelectedListener {
			override fun onItemSelected(
				itemViewHolder: Presenter.ViewHolder?, item: Any?,
				rowViewHolder: RowPresenter.ViewHolder?, row: Row?
			) {
				val position: Int = gridSelectedPosition
				v("row selected position %s", position)
				if (position < 0) {
					mGridView?.let { grid ->
						grid.post { if (mGridView === grid && viewBinding != null) applyPendingSelectedPosition() }
					}
					return
				}
				if (mRestoringSelectedPosition && mPendingSelectedPosition >= 0 && position != mPendingSelectedPosition) {
					applyPendingSelectedPosition()
					return
				}
				if (position != mSelectedPosition) {
					mSelectedPosition = position
				}
				mPendingSelectedPosition = position
				// Update the counter
				updateCounter(position + 1)
				if (position >= 0) {
					mSelectedListener.onItemSelected(itemViewHolder, item, rowViewHolder, row)
				}
			}
		}

	fun updateCounter(position: Int) {
		if (mAdapter != null) {
			binding.counter.setText(
				MessageFormat.format(
					"{0} | {1}",
					position,
					mAdapter!!.totalItems
				)
			)
		}
	}

	private fun setRowDef(rowDef: BrowseRowDef) {
		if (mRowDef == null || mRowDef.hashCode() != rowDef.hashCode()) {
			mDirty = true
		}
		mRowDef = rowDef
	}

	private fun setDefaultGridRowCols(posterSize: PosterSize, imageType: ImageType) {
		if (mGridPresenter is ComposeBrowseListPresenter) return

		// HINT: use uneven Rows/Cols if possible, so selected middle lines up with TV middle!
		val spanCount: Int
		if (mGridPresenter is ComposeVerticalGridPresenter) {
			when (posterSize) {
				PosterSize.SMALLEST -> spanCount =
					if (imageType == ImageType.BANNER) 6 else if (imageType == ImageType.THUMB) 11 else 15

				PosterSize.SMALL -> spanCount =
					if (imageType == ImageType.BANNER) 5 else if (imageType == ImageType.THUMB) 9 else 13

				PosterSize.MED -> spanCount =
					if (imageType == ImageType.BANNER) 4 else if (imageType == ImageType.THUMB) 7 else 11

				PosterSize.LARGE -> spanCount =
					if (imageType == ImageType.BANNER) 3 else if (imageType == ImageType.THUMB) 5 else 7

				PosterSize.X_LARGE -> spanCount =
					if (imageType == ImageType.BANNER) 2 else if (imageType == ImageType.THUMB) 3 else 5

			}
		} else if (mGridPresenter is HorizontalGridPresenter) {
			when (posterSize) {
				PosterSize.SMALLEST -> spanCount =
					if (imageType == ImageType.BANNER) 13 else if (imageType == ImageType.THUMB) 7 else 5

				PosterSize.SMALL -> spanCount =
					if (imageType == ImageType.BANNER) 11 else if (imageType == ImageType.THUMB) 6 else 4

				PosterSize.MED -> spanCount =
					if (imageType == ImageType.BANNER) 9 else if (imageType == ImageType.THUMB) 5 else 3

				PosterSize.LARGE -> spanCount =
					if (imageType == ImageType.BANNER) 7 else if (imageType == ImageType.THUMB) 4 else 2

				PosterSize.X_LARGE -> spanCount =
					if (imageType == ImageType.BANNER) 5 else if (imageType == ImageType.THUMB) 2 else 1

			}
		} else {
			return
		}
		(mGridPresenter as BrowseCardGridPresenter).setSpanCount(spanCount)
	}

	private fun setAutoCardGridValues() {
		val presenter = mGridPresenter ?: run {
			e("Invalid presenter, cannot calculate CardGridValues")
			return
		}
		val layout = calculateBrowseGridLayout(
			presenter, mGridWidth, mGridHeight, mCardFocusScale, mImageType, mFolder,
			mShowCardTitles, mCardSpacing
		)
		if (presenter !is ComposeBrowseListPresenter && mCardHeight != layout.cardHeight) mDirty = true
		mCardHeight = layout.cardHeight
		mGridItemSpacingHorizontal = layout.horizontalSpacing
		mGridItemSpacingVertical = layout.verticalSpacing
		mGridPaddingLeft = layout.paddingLeft
		mGridPaddingTop = layout.paddingTop
		mCardsScreenEst = layout.cardsScreenEstimate
		mCardsScreenStride = layout.cardsScreenStride
	}

	private fun setupQueries() {
		if (mFolder.type == BaseItemKind.USER_VIEW || mFolder.type == BaseItemKind.COLLECTION_FOLDER) {
			val type: CollectionType =
				mFolder.collectionType ?: CollectionType.UNKNOWN
			if (type == CollectionType.MUSIC) {
				//Special queries needed for album artists
				val includeType = requireArguments().getString(Extras.IncludeType)
				if (includeType == "AlbumArtist") {
					setRowDef(
						BrowseRowDef(
							"",
							BrowsingUtils.createAlbumArtistsRequest(mParentId),
							CHUNK_SIZE_MINIMUM,
							emptyArray<ChangeTriggerType>()
						)
					)
					return
				} else if (includeType == "Artist") {
					setRowDef(
						BrowseRowDef(
							"",
							BrowsingUtils.createArtistsRequest(mParentId),
							CHUNK_SIZE_MINIMUM,
							emptyArray<ChangeTriggerType>()
						)
					)
					return
				}
			}
		}

		setRowDef(
			BrowseRowDef(
				"",
				BrowsingUtils.createBrowseGridItemsRequest(mFolder),
				CHUNK_SIZE_MINIMUM,
				false,
				true
			)
		)
	}

	override fun onResume() {
		super.onResume()

		if (!mPreferencesLoaded) {
			justLoaded = false
			return
		}

		if (mAlphabetPickerController != null) mAlphabetPickerController!!.cancel()

		val posterSizeSetting = libraryPreferences.get<PosterSize>(posterSize)
		var imageType = libraryPreferences.get<ImageType>(imageType)
		val gridDirection = libraryPreferences.get<GridDirection>(gridDirection)
		val cardSpacing = libraryPreferences.get<LibraryCardSpacing>(cardSpacing)
		val viewStyle = libraryPreferences.get<LibraryViewStyle>(viewStyle)
		var showCardTitles = libraryPreferences.get<Boolean>(showCardTitles)
		if (viewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE) {
			imageType = ImageType.POSTER
			showCardTitles = false
		}

		if (mImageType != imageType || mPosterSizeSetting != posterSizeSetting || mGridDirection != gridDirection || mCardSpacing != cardSpacing || mViewStyle != viewStyle || mShowCardTitles != showCardTitles || mDirty) {
			determiningPosterSize = true

			mImageType = imageType
			mPosterSizeSetting = posterSizeSetting
			mGridDirection = gridDirection
			mCardSpacing = cardSpacing
			mViewStyle = viewStyle
			mShowCardTitles = showCardTitles

			if (mViewStyle == LibraryViewStyle.KALEIDESCAPE_STYLE && mGridPresenter !is KaleidescapeStylePresenter) {
				setGridPresenter(KaleidescapeStylePresenter())
			} else if (mViewStyle == LibraryViewStyle.DENSE_LIST && mGridPresenter !is ComposeBrowseListPresenter) {
				setGridPresenter(ComposeBrowseListPresenter())
			} else if (mViewStyle == LibraryViewStyle.CARDS && mGridDirection == GridDirection.VERTICAL && (mGridPresenter !is ComposeVerticalGridPresenter)) {
				setGridPresenter(ComposeVerticalGridPresenter())
			} else if (mViewStyle == LibraryViewStyle.CARDS && mGridDirection == GridDirection.HORIZONTAL &&
				(mGridPresenter !is HorizontalGridPresenter || mGridPresenter is KaleidescapeStylePresenter)
			) {
				setGridPresenter(HorizontalGridPresenter())
			}
			setDefaultGridRowCols(mPosterSizeSetting, mImageType)
			setAutoCardGridValues()
			configureNavigationControls()
			createGrid()
			loadGrid()
			addTools()
			determiningPosterSize = false
		}

		if (!justLoaded) {
			//Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
			if (mAdapter != null) {
				mHandler.postDelayed(Runnable {
					if (viewBinding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@Runnable
					if (mAdapter != null && mAdapter!!.size() > 0) {
						val selectedPosition = mSelectedPosition
						if (mAdapter!!.ReRetrieveIfNeeded()) {
							if (selectedPosition >= 0) mPendingSelectedPosition = selectedPosition
						} else {
							refreshCurrentItem()
						}
					}
				}, 500)
			}
		} else {
			justLoaded = false
		}
		updateAlphabetPickerVisibility()
		restoreOverlayFocus()
	}

	private fun restoreOverlayFocus() {
		if (viewBinding == null || mOverlayFocusTarget == OVERLAY_FOCUS_NONE) return

		val target =
			if (mOverlayFocusTarget == OVERLAY_FOCUS_FILTERS) mFilterButton else mSettingsButton
		mOverlayFocusTarget = OVERLAY_FOCUS_NONE
		if (target != null) target.post { if (viewBinding != null && target.isAttachedToWindow) target.requestFocus() }
	}

	private fun buildAdapter() {
		imagePrefetcher.cancel()
		mAdapter?.cancelRetrieval()
		mCardPresenter = CardPresenter(
			false,
			mImageType,
			mCardHeight,
			true,
			shouldShowBrowseCardInfo(mViewStyle, mShowCardTitles)
		)
		mPendingSelectedPosition = -1

		d(
			"buildAdapter cardHeight <%s> getCardWidthBy <%s> chunks <%s> type <%s>",
			mCardHeight,
			getCardWidthBy(mCardHeight.toDouble(), mImageType, mFolder).toInt(),
			mRowDef!!.chunkSize,
			mRowDef!!.queryType.toString()
		)

		val adapter = createBrowseGridAdapter(
			requireContext(), mRowDef!!, mCardPresenter!!, mCardsScreenEst, mCardsScreenStride
		)
		mAdapter = adapter
		mDirty = false

		val filters =
			(if (mTemporaryFilters != null) mTemporaryFilters else org.jellyfin.androidtv.data.model.FilterOptions.fromStored(
				libraryPreferences.get<kotlin.String>(LibraryPreferences.Companion.filters),
				libraryPreferences.get<kotlin.Boolean>(LibraryPreferences.Companion.filterFavoritesOnly),
				libraryPreferences.get<kotlin.Boolean>(LibraryPreferences.Companion.filterUnwatchedOnly)
			))!!

		adapter.setRetrieveFinishedListener(Runnable {
				if (mAdapter !== adapter || viewBinding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@Runnable
				setStatusText(mainTitle)
				if (mCurrentItem == null) { // don't mess-up pos via loadMoreItemsIfNeeded
					setItem(null)
					updateCounter(if (adapter.totalItems > 0) 1 else 0)
				}
				updateAlphabetPickerVisibility()
				if (mQueryChangePending) {
					val position = nextSelectionAfterQueryChange(adapter.itemsLoaded)
					mQueryChangePending = false
					mSelectedPosition = position
					mPendingSelectedPosition = position
					val activeFilters = adapter.getFilters()
					if (shouldKeepToolbarFocusable(
							adapter.itemsLoaded,
							activeFilters != null && !activeFilters.isEmpty
						) && mFilterButton != null
					) {
						mFilterButton?.let { button -> button.post { if (mFilterButton === button && button.isAttachedToWindow) button.requestFocus() } }
					}
				}
				if (adapter.itemsLoaded == 0) {
					if (mGridView?.hasFocus() == true) mActiveToolBar?.requestFocus()
					mGridView?.isFocusable = false
					mHandler.postDelayed(Runnable {
						if (mAdapter !== adapter || viewBinding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@Runnable
						binding.title.setText(mainTitle)
					}, 500)
				} else if (mGridView != null) {
					mGridView?.isFocusable = true
					applyPendingSelectedPosition()
					keepGridFocused()
				}
		})
		adapter.setFilters(filters)

		updateAdapter()
	}

	fun loadGrid() {
		val adapterRebuilt = mCardPresenter == null || mAdapter == null || mDirty
		if (adapterRebuilt) {
			buildAdapter()
		}

		mAdapter!!.setSorting(
			libraryPreferences.get<ItemSortBy>(sortBy),
			libraryPreferences.get<SortOrder>(sortOrder)
		)
		if (mSelectedPosition >= 0) mPendingSelectedPosition = mSelectedPosition
		if (shouldRetrieveGrid(adapterRebuilt, mQueryChangePending, mAdapter!!.itemsLoaded)) {
			mAdapter!!.Retrieve()
		} else {
			applyPendingSelectedPosition()
			setStatusText(mainTitle)
			updateCounter(mSelectedPosition + 1)
			keepGridFocused()
		}
		updateAlphabetPickerVisibility()
	}

	private var mSortButton: ImageButton? = null
	private var mFilterButton: ImageButton? = null
	private var mSettingsButton: ImageButton? = null
	private var mActiveToolBar: ViewGroup? = null

	private fun updateDisplayPrefs() {
		// Shortcut filters belong to this browser visit, so leave the library's saved filters alone.
		if (mTemporaryFilters != null) mTemporaryFilters = mAdapter!!.getFilters()
		runOnLifecycle(lifecycle) {
			if (mTemporaryFilters == null) {
				libraryPreferences.set<Boolean>(
					filterFavoritesOnly,
					mAdapter!!.getFilters()!!.isFavoriteOnly()
				)
				libraryPreferences.set<Boolean>(
					filterUnwatchedOnly,
					mAdapter!!.getFilters()!!.isUnwatchedOnly()
				)
				libraryPreferences.set<String>(filters, mAdapter!!.getFilters()!!.encode())
			}
			libraryPreferences.set<ItemSortBy>(sortBy, mAdapter!!.sortBy!!)
			libraryPreferences.set<SortOrder>(sortOrder, mAdapter!!.sortOrder!!)
			libraryPreferences.commit()
		}
	}

	private fun addTools() {
		binding.toolBar.removeAllViews()
		binding.verticalToolBar.removeAllViews()

		val size = convertDpToPixel(requireContext(), 26)
		val padding = convertDpToPixel(requireContext(), 3)

		if (mTemporaryFilters == null && (mFolder.collectionType == CollectionType.MOVIES || mFolder.collectionType == CollectionType.TVSHOWS)) {
			val genresButton = Button(requireContext(), null, 0, R.style.Button_Default)
			genresButton.setText(R.string.lbl_genres)
			genresButton.setAllCaps(false)
			genresButton.setTextSize(14f)
			genresButton.setSingleLine(true)
			genresButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
				R.drawable.ic_masks,
				0,
				0,
				0
			)
			TextViewCompat.setCompoundDrawableTintList(genresButton, genresButton.textColors)
			val horizontalPadding = convertDpToPixel(requireContext(), 8)
			genresButton.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size)
			params.setMarginEnd(horizontalPadding)
			genresButton.setLayoutParams(params)
			genresButton.setOnClickListener(View.OnClickListener { v: View? ->
				navigationRepository.value.navigate(
					Destinations.libraryByGenres(
						mFolder,
						(if (mFolder.collectionType == CollectionType.TVSHOWS) BaseItemKind.SERIES else BaseItemKind.MOVIE).serialName
					)
				)
			})
			binding.toolBar.setVisibility(View.VISIBLE)
			binding.toolBar.addView(genresButton)
		}

		mSortButton = ImageButton(requireContext(), null, 0, R.style.Button_Icon)
		mSortButton!!.setImageResource(R.drawable.ic_sort)
		configureToolButton(mSortButton!!, size, padding)
		mSortButton!!.setOnClickListener(object : View.OnClickListener {
			override fun onClick(v: View?) {
				//Create sort menu
				val sortMenu = PopupMenu(getActivity(), mActiveToolBar, Gravity.END)
				for (entry in sortOptions.entries) {
					val active = entry.value.value == mAdapter!!.sortBy
					val label =
						entry.value.name + (if (active) " " + mAdapter!!.sortOrder!!.menuArrow else "")
					val item = sortMenu.getMenu().add(0, entry.key, entry.key, label)
					item.setChecked(active)
				}
				sortMenu.getMenu().setGroupCheckable(0, true, true)
				sortMenu.setOnMenuItemClickListener(object : PopupMenu.OnMenuItemClickListener {
					override fun onMenuItemClick(item: MenuItem): Boolean {
						val selected =
							Objects.requireNonNull<BrowseSortOption>(sortOptions.get(item.getItemId()))
						val next = selectSort(
							LibrarySortState(mAdapter!!.sortBy!!, mAdapter!!.sortOrder!!),
							LibrarySortOption(selected.value, selected.order)
						)
						prepareForQueryChange()
						mAdapter!!.setSorting(next.field, next.direction)
						mAdapter!!.Retrieve()
						item.setChecked(true)
						updateDisplayPrefs()
						return true
					}
				})
				sortMenu.show()
			}
		})
		mSortButton!!.setContentDescription(getString(R.string.lbl_sort_by))

		mActiveToolBar!!.addView(mSortButton)

		if (mRowDef!!.queryType == QueryType.Items) {
			val filtersVisible: MutableStateFlow<Boolean> =
				this@BrowseGridFragment.createFiltersVisibility()
			mFilterButton = ImageButton(requireContext(), null, 0, R.style.Button_Icon)
			mFilterButton!!.setImageResource(R.drawable.ic_filter_list)
			mFilterButton!!.setActivated(mAdapter!!.getFilters() != null && !mAdapter!!.getFilters()!!.isEmpty)
			configureToolButton(mFilterButton!!, size, padding)
			mFilterButton!!.setOnClickListener(View.OnClickListener { v: View? ->
				mOverlayFocusTarget = OVERLAY_FOCUS_FILTERS
				filtersVisible.value = true
			})
			mFilterButton!!.setContentDescription(getString(R.string.filters))
			mActiveToolBar!!.addView(mFilterButton)

			val includeTypes: MutableSet<BaseItemKind> = HashSet<BaseItemKind>()
			if (mRowDef!!.query.includeItemTypes != null) {
				includeTypes.addAll(mRowDef!!.query.includeItemTypes!!)
			}
			this@BrowseGridFragment
				.addFilters(
					binding.filters,
					filtersVisible,
					{ (if (mAdapter!!.getFilters() != null) mAdapter!!.getFilters() else org.jellyfin.androidtv.data.model.FilterOptions())!! },
					mParentId,
					includeTypes,
					{ filters -> applyFilters(filters) }
				)
		} else {
			mFilterButton = null
		}

		if (mFolder.displayPreferencesId != null) {
			val settingsVisible: MutableStateFlow<Boolean> =
				this@BrowseGridFragment.createSettingsVisibility()
			mSettingsButton = ImageButton(requireContext(), null, 0, R.style.Button_Icon)
			mSettingsButton!!.setImageResource(R.drawable.ic_settings)
			configureToolButton(mSettingsButton!!, size, padding)
			mSettingsButton!!.setOnClickListener(object : View.OnClickListener {
				override fun onClick(v: View?) {
					mOverlayFocusTarget = OVERLAY_FOCUS_SETTINGS
					settingsVisible.value = true
				}
			})
			mSettingsButton!!.setContentDescription(getString(R.string.lbl_settings))
			mActiveToolBar!!.addView(mSettingsButton)
			this@BrowseGridFragment.addSettings(
				binding.settings,
				mFolder.id,
				mFolder.displayPreferencesId!!,
				settingsVisible
			)
		}

		configureNavigationControlsFocus()
	}

	private fun prepareForQueryChange() {
		cancelSelectionRestore()
		mSelectedPosition = -1
		mPendingSelectedPosition = -1
		mCurrentItem = null
		mQueryChangePending = true
	}

	private fun applyFilters(filters: FilterOptions) {
		if (!shouldApplyLibraryFilters(mAdapter!!.getFilters(), filters)) return
		prepareForQueryChange()
		mAdapter!!.setFilters(filters)
		if (mFilterButton != null) mFilterButton!!.setActivated(!filters.isEmpty)
		mAdapter!!.Retrieve()
		updateAlphabetPickerVisibility()
		updateDisplayPrefs()
	}

	private fun configureToolButton(button: ImageButton, size: Int, padding: Int) {
		button.setLayoutParams(LinearLayout.LayoutParams(size, size))
		button.setPadding(padding, padding, padding, padding)
		button.setAdjustViewBounds(true)
	}

	private fun setupEventListeners() {
		if (mGridPresenter != null) {
			if (mGridPresenter is HorizontalGridPresenter) (mGridPresenter as HorizontalGridPresenter).setOnItemViewClickedListener(
				mClickedListener
			)
			else if (mGridPresenter is ComposeBrowseListPresenter) (mGridPresenter as ComposeBrowseListPresenter).setOnItemViewClickedListener(
				mClickedListener
			)
			else if (mGridPresenter is ComposeVerticalGridPresenter) (mGridPresenter as ComposeVerticalGridPresenter).setOnItemViewClickedListener(
				mClickedListener
			)
		}
		mClickedListener.registerListener(ItemViewClickedListener())
		mSelectedListener.registerListener(ItemViewSelectedListener())

		readCustomMessagesOnLifecycle(
			lifecycle,
			customMessageRepository.value
		) { message ->
			if (message == CustomMessage.RefreshCurrentItem) refreshCurrentItem()
		}
	}

	private fun refreshCurrentItem() {
		val item = mCurrentItem ?: return
		val adapter = mAdapter ?: return
		if (viewBinding == null) return
		d("Refresh item \"%s\"", item.getFullName(requireContext()))
		adapter.refreshItem(api.value, viewLifecycleOwner, item) { refreshedItem: BaseRowItem? ->
			if (viewBinding == null || mAdapter !== adapter || mCurrentItem !== item) return@refreshItem
			updateAlphabetPickerVisibility()
			//Now - if filtered make sure we still pass
			val filters = adapter.getFilters()
			if (filters == null || refreshedItem == null) return@refreshItem Unit
			mCurrentItem = refreshedItem
			if (shouldRemoveFilteredItem(
					filters,
					refreshedItem.isFavorite,
					refreshedItem.isPlayed
				)
			) {
				// if we are about to remove the current item, throw focus to toolbar so framework doesn't crash
				mActiveToolBar?.requestFocus()
				val removedPosition = adapter.indexOf(refreshedItem)
				if (removedPosition >= 0) {
					adapter.remove(refreshedItem)
					adapter.totalItems = max(0, adapter.totalItems - 1)
					updateCounter(min(removedPosition + 1, adapter.totalItems))
				}
				mCurrentItem = null
			}
		}
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?, item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?, row: Row?
		) {
			if (item !is BaseRowItem) return
			itemLauncher.value.launch(item, mAdapter, requireContext())
		}
	}

	private val mDelayedSetItem: Runnable = object : Runnable {
		override fun run() {
			if (viewBinding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

			setItem(mCurrentItem)
		}
	}

	private val mDelayedSetBackground: Runnable = object : Runnable {
		override fun run() {
			if (viewBinding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

			mCurrentItem?.let { backgroundService.value.setBackground(it.baseItem) }
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?, item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?, row: Row?
		) {
			mHandler.removeCallbacks(mDelayedSetItem)
			mHandler.removeCallbacks(mDelayedSetBackground)
			binding.genres.setText("")
			binding.infoRow.removeAllViews()
			if (item !is BaseRowItem) {
				mCurrentItem = null
				binding.title.setText(mainTitle)
				//fill in default background
				backgroundService.value.clearBackgrounds()
			} else {
				mCurrentItem = item
				binding.title.setText(mCurrentItem!!.getName(requireContext()))
				if (mViewStyle != LibraryViewStyle.KALEIDESCAPE_STYLE) {
					mHandler.postDelayed(mDelayedSetItem, VIEW_SELECT_UPDATE_DELAY.toLong())
					mHandler.postDelayed(mDelayedSetBackground, BACKGROUND_UPDATE_DELAY.toLong())
				}

				val position = mAdapter!!.indexOf(mCurrentItem!!)
				if (!determiningPosterSize) mAdapter!!.loadMoreItemsIfNeeded(position)
				prefetchCardImages(position)
			}
		}
	}

	companion object {
		private const val CHUNK_SIZE_MINIMUM = 80

		private const val OVERLAY_FOCUS_NONE = 0
		private const val OVERLAY_FOCUS_FILTERS = 1
		private const val OVERLAY_FOCUS_SETTINGS = 2


	}
}
