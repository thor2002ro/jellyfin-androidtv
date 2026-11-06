package org.jellyfin.androidtv.ui.browsing;

import static org.koin.java.KoinJavaComponent.inject;

import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.lifecycle.Lifecycle;

import coil3.ImageLoader;
import coil3.request.ImageRequest;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.ChangeTriggerType;
import org.jellyfin.androidtv.constant.CustomMessage;
import org.jellyfin.androidtv.constant.Extras;
import org.jellyfin.androidtv.constant.GridDirection;
import org.jellyfin.androidtv.constant.ImageType;
import org.jellyfin.androidtv.constant.PosterSize;
import org.jellyfin.androidtv.constant.QueryType;
import org.jellyfin.androidtv.data.model.FilterOptions;
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest;
import org.jellyfin.androidtv.data.repository.CustomMessageRepository;
import org.jellyfin.androidtv.data.service.BackgroundService;
import org.jellyfin.androidtv.databinding.HorizontalGridBrowseBinding;
import org.jellyfin.androidtv.preference.LibraryPreferences;
import org.jellyfin.androidtv.preference.PreferencesRepository;
import org.jellyfin.androidtv.ui.AlphaPickerView;
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapterHelperKt;
import org.jellyfin.androidtv.ui.presentation.CardPresenter;
import org.jellyfin.androidtv.ui.presentation.ComposeVerticalGridPresenter;
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter;
import org.jellyfin.androidtv.util.CoroutineUtils;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.KeyProcessor;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.EmptyResponse;
import org.jellyfin.androidtv.util.apiclient.JellyfinImage;
import org.jellyfin.androidtv.util.apiclient.JellyfinImageKt;
import org.jellyfin.sdk.api.client.ApiClient;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.api.CollectionType;
import org.jellyfin.sdk.model.api.ItemSortBy;
import org.jellyfin.sdk.model.api.SortOrder;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import kotlin.Lazy;
import kotlin.Unit;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.serialization.json.Json;
import timber.log.Timber;

public class BrowseGridFragment extends Fragment implements View.OnKeyListener {
    private final static int CHUNK_SIZE_MINIMUM = 80;

    private String mainTitle;
    private FragmentActivity mActivity;
    private BaseRowItem mCurrentItem;
    private CompositeClickedListener mClickedListener = new CompositeClickedListener();
    private CompositeSelectedListener mSelectedListener = new CompositeSelectedListener();
    private final Handler mHandler = new Handler();
    private int mCardHeight;
    private BrowseRowDef mRowDef;
    private CardPresenter mCardPresenter;

    private boolean justLoaded = true;
    private PosterSize mPosterSizeSetting = PosterSize.MED;
    private ImageType mImageType = ImageType.POSTER;
    private GridDirection mGridDirection = GridDirection.HORIZONTAL;
    private boolean determiningPosterSize = false;

    private UUID mParentId;
    private BaseItemDto mFolder;
    private LibraryPreferences libraryPreferences;

    private HorizontalGridBrowseBinding binding;
    private ItemRowAdapter mAdapter;
    private Presenter mGridPresenter;
    private Presenter.ViewHolder mGridViewHolder;
    private View mGridView;
    private int mVerticalColumnCount;
    private int mSelectedPosition = -1;
    private int mGridHeight = -1;
    private int mGridWidth = -1;
    private int mGridItemSpacingHorizontal = 0;
    private int mGridItemSpacingVertical = 0;
    private int mGridPaddingLeft = 0;
    private int mGridPaddingTop = 0;

    private final Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    private final Lazy<PreferencesRepository> preferencesRepository = inject(PreferencesRepository.class);
    private final Lazy<CustomMessageRepository> customMessageRepository = inject(CustomMessageRepository.class);
    private final Lazy<ItemLauncher> itemLauncher = inject(ItemLauncher.class);
    private final Lazy<KeyProcessor> keyProcessor = inject(KeyProcessor.class);
    private final Lazy<ApiClient> api = inject(ApiClient.class);
    private final Lazy<ImageLoader> imageLoader = inject(ImageLoader.class);

    private int mCardsScreenEst = 0;
    private int mCardsScreenStride = 0;
    private double mCardFocusScale = 1.15; // 115%, just a default we use the resource card_scale_focus otherwise
    private final int MIN_NUM_CARDS = 5; // minimum number of visible cards we allow, this results in more empty space
    private final double CARD_SPACING_PCT = 1.0; // 100% expressed as relative to the padding_left/top, which depends on the mCardFocusScale and AspectRatio
    private final double CARD_SPACING_HORIZONTAL_BANNER_PCT = 0.5; // 50% allow horizontal card overlapping for banners, otherwise spacing is too large
    private final int VIEW_SELECT_UPDATE_DELAY = 250; // delay in ms until we update the top-row info for a selected item
    private final int SELECTION_RESTORE_WINDOW_MS = 1500;

    private boolean mDirty = true; // CardHeight, RowDef or GridSize changed
    private boolean mPreferencesLoaded = false;
    private boolean mViewInitialized = false;
    private int mLastImagePrefetchPosition = -1;
    private int mLastImagePrefetchItemsLoaded = -1;
    private int mPendingSelectedPosition = -1;
    private boolean mRestoringSelectedPosition = false;
    private int mSelectionRestoreGeneration = 0;
    private final Set<String> mPrefetchedImageUrls = new HashSet<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // init with some working defaults
        DisplayMetrics display = requireContext().getResources().getDisplayMetrics();
        // top + bottom in dp, elements scale with density so adjust accordingly
        mGridHeight = Math.round(display.heightPixels / getResources().getDisplayMetrics().density - 130.6f);
        mGridWidth = Math.round(display.widthPixels / getResources().getDisplayMetrics().density);


        mActivity = getActivity();

        mFolder = Json.Default.decodeFromString(BaseItemDto.Companion.serializer(), getArguments().getString(Extras.Folder));
        mParentId = mFolder.getId();
        mainTitle = mFolder.getName();
        mCardFocusScale = getResources().getFraction(R.fraction.card_scale_focus, 1, 1);

        sortOptions = new HashMap<>();
        {
            boolean isTvShows = mFolder.getCollectionType() == CollectionType.TVSHOWS;

            sortOptions.put(0, new SortOption(getString(R.string.lbl_name), ItemSortBy.SORT_NAME, SortOrder.ASCENDING));
            if (isTvShows) {
                sortOptions.put(1, new SortOption(getString(R.string.lbl_date_show_added), ItemSortBy.DATE_CREATED, SortOrder.DESCENDING));
            } else {
                sortOptions.put(1, new SortOption(getString(R.string.lbl_date_added), ItemSortBy.DATE_CREATED, SortOrder.DESCENDING));
            }
            sortOptions.put(2, new SortOption(getString(R.string.lbl_premier_date), ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING));
            sortOptions.put(3, new SortOption(getString(R.string.lbl_rating), ItemSortBy.OFFICIAL_RATING, SortOrder.ASCENDING));
            sortOptions.put(4, new SortOption(getString(R.string.lbl_community_rating), ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING));
            sortOptions.put(5, new SortOption(getString(R.string.lbl_critic_rating), ItemSortBy.CRITIC_RATING, SortOrder.DESCENDING));

            if (isTvShows) {
                sortOptions.put(6, new SortOption(getString(R.string.lbl_last_played), ItemSortBy.SERIES_DATE_PLAYED, SortOrder.DESCENDING));
                sortOptions.put(7, new SortOption(getString(R.string.lbl_date_episode_added), ItemSortBy.DATE_LAST_CONTENT_ADDED, SortOrder.DESCENDING));
            } else {
                sortOptions.put(6, new SortOption(getString(R.string.lbl_last_played), ItemSortBy.DATE_PLAYED, SortOrder.DESCENDING));
                if (mFolder.getCollectionType() == CollectionType.MOVIES) {
                    sortOptions.put(7, new SortOption(getString(R.string.lbl_runtime), ItemSortBy.RUNTIME, SortOrder.ASCENDING));
                }
            }
        }

        BrowseGridFragmentHelperKt.loadLibraryPreferences(this, preferencesRepository.getValue(), Objects.requireNonNull(mFolder.getDisplayPreferencesId()), preferences -> {
            initializeLibraryPreferences(preferences);
            return Unit.INSTANCE;
        });
    }

    private void initializeLibraryPreferences(LibraryPreferences preferences) {
        libraryPreferences = preferences;
        mPosterSizeSetting = libraryPreferences.get(LibraryPreferences.Companion.getPosterSize());
        mImageType = libraryPreferences.get(LibraryPreferences.Companion.getImageType());
        mGridDirection = libraryPreferences.get(LibraryPreferences.Companion.getGridDirection());

        setGridPresenter(createGridPresenter(mGridDirection));

        setDefaultGridRowCols(mPosterSizeSetting, mImageType);
        setAutoCardGridValues();
        setupQueries();
        setupEventListeners();

        mPreferencesLoaded = true;
        initializeView();
    }

    static Presenter createGridPresenter(GridDirection direction) {
        return direction == GridDirection.VERTICAL
                ? new ComposeVerticalGridPresenter()
                : new HorizontalGridPresenter();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = HorizontalGridBrowseBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (mainTitle != null) binding.title.setText(mainTitle);
        initializeView();
    }

    private void initializeView() {
        if (!mPreferencesLoaded || binding == null || mViewInitialized) return;

        configureNavigationControls();
        createGrid();
        loadGrid();
        addTools();

        mViewInitialized = true;
    }

    private void configureNavigationControls() {
        boolean vertical = GridDirection.VERTICAL.equals(mGridDirection);
        binding.toolBar.setVisibility(vertical ? View.GONE : View.VISIBLE);
        binding.verticalToolBar.setVisibility(vertical ? View.VISIBLE : View.GONE);
        binding.alphaPickerVertical.setVertical(true);
        binding.alphaPickerHorizontal.setVisibility(vertical ? View.GONE : View.VISIBLE);
        binding.alphaPickerVertical.setVisibility(vertical ? View.VISIBLE : View.GONE);
        mActiveToolBar = vertical ? binding.verticalToolBar : binding.toolBar;
        updateNavigationControlsSpacing();

        AlphaPickerView picker = vertical ? binding.alphaPickerVertical : binding.alphaPickerHorizontal;
        picker.setOnAlphaSelected(letter -> {
            mAdapter.setStartLetter(letter.toString());
            loadGrid();
            return null;
        });
    }

    private void updateAlphabetPickerVisibility() {
        binding.alphaPickerHorizontal.setVisibility(
                shouldShowAlphabetPicker(GridDirection.HORIZONTAL, mGridDirection) ? View.VISIBLE : View.GONE
        );
        binding.alphaPickerVertical.setVisibility(
                shouldShowAlphabetPicker(GridDirection.VERTICAL, mGridDirection) ? View.VISIBLE : View.GONE
        );
        updateNavigationControlsSpacing();
    }

    private void updateNavigationControlsSpacing() {
        int gridHostStartMargin = Utils.convertDpToPixel(requireContext(), getGridHostSideMarginDp(mGridDirection));
        int gridHostEndMargin = Utils.convertDpToPixel(requireContext(), getGridHostEndMarginDp(mGridDirection));
        ViewGroup.MarginLayoutParams gridParams = (ViewGroup.MarginLayoutParams) binding.rowsFragment.getLayoutParams();
        gridParams.setMarginStart(gridHostStartMargin);
        gridParams.setMarginEnd(gridHostEndMargin);
        gridParams.bottomMargin = Utils.convertDpToPixel(requireContext(), getGridHostBottomMarginDp(mGridDirection));
        binding.rowsFragment.setLayoutParams(gridParams);
    }

	static int getGridHostSideMarginDp(GridDirection direction) {
		return GridDirection.VERTICAL.equals(direction) ? 20 : 0;
	}

	static int getGridHostEndMarginDp(GridDirection direction) {
		return GridDirection.VERTICAL.equals(direction) ? 28 : 0;
	}

	static int getGridHostBottomMarginDp(GridDirection direction) {
		return GridDirection.VERTICAL.equals(direction) ? 24 : 35;
	}

    static int pixelsToDp(int pixels, float density) {
        return Math.round(pixels / density);
    }

    static boolean shouldShowAlphabetPicker(GridDirection pickerDirection, GridDirection gridDirection) {
        return pickerDirection.equals(gridDirection);
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        return keyProcessor.getValue().handleKey(keyCode, mCurrentItem, mActivity);
    }

    @Override
    public void onDestroyView() {
        releaseGrid();
        super.onDestroyView();

        binding = null;
        mViewInitialized = false;
    }

    private void createGrid() {
        releaseGrid();
        mGridViewHolder = mGridPresenter.onCreateViewHolder(binding.rowsFragment);
        if (mGridViewHolder instanceof HorizontalGridPresenter.ViewHolder) {
            HorizontalGridPresenter presenter = (HorizontalGridPresenter) mGridPresenter;
            View gridView = ((HorizontalGridPresenter.ViewHolder) mGridViewHolder).getGridView();
            mGridView = gridView;
            ViewGroup.MarginLayoutParams titleMargin = (ViewGroup.MarginLayoutParams) binding.title.getLayoutParams();
            ViewGroup.MarginLayoutParams clockMargin = (ViewGroup.MarginLayoutParams) binding.clock.getLayoutParams();
            float density = getResources().getDisplayMetrics().density;
            presenter.configure(
                    mImageType,
                    mCardHeight,
                    presenter.getNumberOfRows(),
                    mGridItemSpacingHorizontal,
                    mGridItemSpacingVertical,
                    pixelsToDp(titleMargin.getMarginStart(), density),
                    pixelsToDp(clockMargin.getMarginEnd(), density),
                    mGridPaddingTop,
                    false,
                    true
            );
        } else if (mGridViewHolder instanceof ComposeVerticalGridPresenter.ViewHolder) {
            ComposeVerticalGridPresenter presenter = (ComposeVerticalGridPresenter) mGridPresenter;
            View gridView = ((ComposeVerticalGridPresenter.ViewHolder) mGridViewHolder).getGridView();
            mGridView = gridView;
            presenter.configure(
                    mImageType,
                    mCardHeight,
                    mVerticalColumnCount,
                    mGridItemSpacingHorizontal,
                    mGridItemSpacingVertical,
                    mGridPaddingLeft
            );
        }
        if (mGridView.getId() == View.NO_ID) mGridView.setId(View.generateViewId());
        mGridView.setFocusable(true);
        mGridView.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) setGridSelectedPosition(Math.max(getGridSelectedPosition(), 0));
        });
        binding.rowsFragment.removeAllViews();
        binding.rowsFragment.addView(mGridViewHolder.view);

        updateAdapter();
    }

    private void cancelSelectionRestore() {
        if (!mRestoringSelectedPosition) return;

        mRestoringSelectedPosition = false;
        mSelectionRestoreGeneration++;
    }

    private int getGridSelectedPosition() {
        if (mGridPresenter instanceof ComposeVerticalGridPresenter) {
            return ((ComposeVerticalGridPresenter) mGridPresenter).getPosition();
        }
        if (mGridPresenter instanceof HorizontalGridPresenter) {
            return ((HorizontalGridPresenter) mGridPresenter).getPosition();
        }
        return -1;
    }

    private void setGridSelectedPosition(int position) {
        if (mGridPresenter instanceof ComposeVerticalGridPresenter) {
            ((ComposeVerticalGridPresenter) mGridPresenter).setPosition(position);
        } else if (mGridPresenter instanceof HorizontalGridPresenter) {
            ((HorizontalGridPresenter) mGridPresenter).setPosition(position);
        }
    }

    private void keepGridFocused() {
        View grid = mGridView;
        if (grid == null || mAdapter == null || mAdapter.getItemsLoaded() <= 0) return;

        grid.post(() -> {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
            View focused = mActivity != null ? mActivity.getCurrentFocus() : null;
            if (isFocusInsideGrid(focused, grid)) return;
            grid.requestFocus();
        });
    }

    private boolean isFocusInsideGrid(View focused, View grid) {
        View view = focused;
        while (view != null) {
            if (view == grid) return true;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private void applyPendingSelectedPosition() {
        if (mAdapter == null || mGridView == null || mAdapter.getItemsLoaded() <= 0) return;

        int position = getSafeSelectedPosition(
                mPendingSelectedPosition,
                mSelectedPosition,
                getGridSelectedPosition(),
                mAdapter.getItemsLoaded()
        );
        if (position < 0) return;

        mSelectedPosition = position;
        mPendingSelectedPosition = position;
        mRestoringSelectedPosition = true;
        int restoreGeneration = ++mSelectionRestoreGeneration;
        setGridSelectedPosition(position);
        mHandler.postDelayed(() -> {
            if (mSelectionRestoreGeneration == restoreGeneration) {
                mRestoringSelectedPosition = false;
            }
        }, SELECTION_RESTORE_WINDOW_MS);
    }

    static int getSafeSelectedPosition(int pendingSelectedPosition, int selectedPosition, int gridSelectedPosition, int itemsLoaded) {
        if (itemsLoaded <= 0) return -1;

        int position = pendingSelectedPosition >= 0
                ? pendingSelectedPosition
                : selectedPosition >= 0
                        ? selectedPosition
                        : Math.max(gridSelectedPosition, 0);

        return Math.min(position, itemsLoaded - 1);
    }

    private void prefetchCardImages(int position) {
        if (mAdapter == null || position < 0 || mAdapter.getItemsLoaded() <= 0) return;

        int itemsLoaded = mAdapter.getItemsLoaded();
        int stride = Math.max(1, mCardsScreenEst);
        if (mLastImagePrefetchPosition >= 0
                && mLastImagePrefetchItemsLoaded == itemsLoaded
                && Math.abs(position - mLastImagePrefetchPosition) < stride) return;
        mLastImagePrefetchPosition = position;
        mLastImagePrefetchItemsLoaded = itemsLoaded;

        int maxHeight = Utils.convertDpToPixel(requireContext(), mCardHeight);
        int maxWidth = Utils.convertDpToPixel(requireContext(), (int) getCardWidthBy(mCardHeight, mImageType, mFolder));
        int start = Math.min(itemsLoaded, position + 1 + Math.max(1, mCardsScreenEst));
        int end = Math.min(itemsLoaded, start + Math.max(1, mCardsScreenStride));
        for (int i = start; i < end; i++) {
            if (!(mAdapter.get(i) instanceof BaseRowItem)) continue;

            JellyfinImage image = ((BaseRowItem) mAdapter.get(i)).getImage(mImageType);
            if (image == null) continue;

            String url = JellyfinImageKt.getUrl(image, api.getValue(), maxWidth, maxHeight, null, null);
            if (!mPrefetchedImageUrls.add(url)) continue;

            imageLoader.getValue().enqueue(new ImageRequest.Builder(requireContext()).data(url).build());
        }
    }

    private void updateAdapter() {
        if (mGridView != null && mAdapter != null) {
            mGridPresenter.onBindViewHolder(mGridViewHolder, mAdapter);
            if (mSelectedPosition >= 0 && mAdapter.getItemsLoaded() > mSelectedPosition) {
                setGridSelectedPosition(mSelectedPosition);
            }
        }
    }

    /**
     * Sets the grid presenter.
     */
    private void setGridPresenter(Presenter gridPresenter) {
        if (gridPresenter == null) {
            throw new IllegalArgumentException("Grid presenter may not be null");
        }
        if (!(gridPresenter instanceof ComposeVerticalGridPresenter) && !(gridPresenter instanceof HorizontalGridPresenter)) {
            throw new IllegalArgumentException("Unsupported grid presenter");
        }

        releaseGrid();
        if (gridPresenter instanceof ComposeVerticalGridPresenter) {
            ComposeVerticalGridPresenter presenter = (ComposeVerticalGridPresenter) gridPresenter;
            presenter.setOnItemViewSelectedListener(mRowSelectedListener);
            presenter.setOnItemViewClickedListener(mClickedListener);
            presenter.setOnDirectionalKeyListener(this::cancelSelectionRestore);
        } else if (gridPresenter instanceof HorizontalGridPresenter) {
            HorizontalGridPresenter presenter = (HorizontalGridPresenter) gridPresenter;
            presenter.setOnItemViewSelectedListener(mRowSelectedListener);
            presenter.setOnItemViewClickedListener(mClickedListener);
            presenter.setOnDirectionalKeyListener(this::cancelSelectionRestore);
        }
        mGridPresenter = gridPresenter;
    }

    private void configureNavigationControlsFocus() {
        if (mGridView == null || mActiveToolBar == null) return;

        boolean vertical = GridDirection.VERTICAL.equals(mGridDirection);
        AlphaPickerView picker = vertical ? binding.alphaPickerVertical : binding.alphaPickerHorizontal;
        picker.setGridFocusTargetId(mGridView.getId());
    }

    static void releaseGrid(@Nullable Presenter presenter, @Nullable Presenter.ViewHolder viewHolder) {
        if (presenter != null && viewHolder != null) presenter.onUnbindViewHolder(viewHolder);
    }

    private void releaseGrid() {
        releaseGrid(mGridPresenter, mGridViewHolder);
        mGridViewHolder = null;
        mGridView = null;
    }

    public void setItem(BaseRowItem item) {
        if (item != null) {
            binding.title.setText(item.getFullName(requireContext()));
            InfoLayoutHelper.addInfoRow(requireContext(), item.getBaseItem(), binding.infoRow, true);
        } else {
            binding.title.setText("");
            binding.infoRow.removeAllViews();
        }
    }

    public class SortOption {
        public String name;
        public ItemSortBy value;
        public SortOrder order;

        public SortOption(String name, ItemSortBy value, SortOrder order) {
            this.name = name;
            this.value = value;
            this.order = order;
        }
    }

    private Map<Integer, SortOption> sortOptions;

    private SortOption getSortOption(ItemSortBy value) {
        for (SortOption sortOption : sortOptions.values()) {
            if (sortOption.value.equals(value)) return sortOption;
        }

        return new SortOption(getString(R.string.lbl_bracket_unknown), ItemSortBy.SORT_NAME, SortOrder.ASCENDING);
    }

    public void setStatusText(String folderName) {
        String text = getString(R.string.lbl_showing) + " ";
        FilterOptions filters = mAdapter.getFilters();
        if (filters == null || (!filters.isFavoriteOnly() && !filters.isUnwatchedOnly())) {
            text += getString(R.string.lbl_all_items);
        } else {
            text += (filters.isUnwatchedOnly() ? getString(R.string.lbl_unwatched) : "") + " " +
                    (filters.isFavoriteOnly() ? getString(R.string.lbl_favorites) : "");
        }

        if (mAdapter.getStartLetter() != null) {
            text += " " + getString(R.string.lbl_starting_with) + " " + mAdapter.getStartLetter();
        }

        text += " " + getString(R.string.lbl_from) + " '" + folderName + "' " + getString(R.string.lbl_sorted_by) + " " + getSortOption(mAdapter.getSortBy()).name;

        binding.statusText.setText(text);
    }

    final private OnItemViewSelectedListener mRowSelectedListener =
            new OnItemViewSelectedListener() {
                @Override
                public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                           RowPresenter.ViewHolder rowViewHolder, Row row) {
                    int position = getGridSelectedPosition();
                    Timber.v("row selected position %s", position);
                    if (position < 0) {
                        mGridView.post(BrowseGridFragment.this::applyPendingSelectedPosition);
                        return;
                    }
                    if (mRestoringSelectedPosition && mPendingSelectedPosition >= 0 && position != mPendingSelectedPosition) {
                        applyPendingSelectedPosition();
                        return;
                    }
                    if (position != mSelectedPosition) {
                        mSelectedPosition = position;
                    }
                    mPendingSelectedPosition = position;
                    // Update the counter
                    updateCounter(position + 1);
                    if (position >= 0) {
                        mSelectedListener.onItemSelected(itemViewHolder, item, rowViewHolder, row);
                    }
                }
            };

    public void updateCounter(int position) {
        if (mAdapter != null) {
            binding.counter.setText(MessageFormat.format("{0} | {1}", position, mAdapter.getTotalItems()));
        }
    }

    private void setRowDef(final BrowseRowDef rowDef) {
        if (mRowDef == null || mRowDef.hashCode() != rowDef.hashCode()) {
            mDirty = true;
        }
        mRowDef = rowDef;
    }

    private double getCardWidthBy(final double cardHeight, ImageType imageType, BaseItemDto folder) {
        switch (imageType) {
            case POSTER:
                // special handling for square posters
                BaseItemKind fType = folder.getType();
                if (fType == BaseItemKind.AUDIO || fType == BaseItemKind.GENRE || fType == BaseItemKind.MUSIC_ALBUM || fType == BaseItemKind.MUSIC_ARTIST || fType == BaseItemKind.MUSIC_GENRE) {
                    return cardHeight;
                } else if (fType == BaseItemKind.COLLECTION_FOLDER && CollectionType.MUSIC.equals(folder.getCollectionType())) {
                    return cardHeight;
                } else {
                    return cardHeight * ImageHelper.ASPECT_RATIO_2_3;
                }
            case THUMB:
                return cardHeight * ImageHelper.ASPECT_RATIO_16_9;
            case BANNER:
                return cardHeight * ImageHelper.ASPECT_RATIO_BANNER;
            default:
                throw new IllegalStateException("Unexpected value: " + imageType);
        }
    }

    private double getCardHeightBy(final double cardWidth, ImageType imageType, BaseItemDto folder) {
        switch (imageType) {
            case POSTER:
                // special handling for square posters
                BaseItemKind fType = folder.getType();
                if (fType == BaseItemKind.AUDIO || fType == BaseItemKind.GENRE || fType == BaseItemKind.MUSIC_ALBUM || fType == BaseItemKind.MUSIC_ARTIST || fType == BaseItemKind.MUSIC_GENRE) {
                    return cardWidth;
                } else if (fType == BaseItemKind.COLLECTION_FOLDER && CollectionType.MUSIC.equals(folder.getCollectionType())) {
                    return cardWidth;
                } else {
                    return cardWidth / ImageHelper.ASPECT_RATIO_2_3;
                }
            case THUMB:
                return cardWidth / ImageHelper.ASPECT_RATIO_16_9;
            case BANNER:
                return cardWidth / ImageHelper.ASPECT_RATIO_BANNER;
            default:
                throw new IllegalArgumentException("Unexpected value: " + imageType);
        }
    }

    private void setDefaultGridRowCols(PosterSize posterSize, ImageType imageType) {
        // HINT: use uneven Rows/Cols if possible, so selected middle lines up with TV middle!
        if (mGridPresenter instanceof ComposeVerticalGridPresenter) {
            int numCols;
            switch (posterSize) {
                case SMALLEST:
                    numCols = imageType.equals(ImageType.BANNER) ? 6 : imageType.equals(ImageType.THUMB) ? 11 : 15;
                    break;
                case SMALL:
                    numCols = imageType.equals(ImageType.BANNER) ? 5 : imageType.equals(ImageType.THUMB) ? 9 : 13;
                    break;
                case MED:
                    numCols = imageType.equals(ImageType.BANNER) ? 4 : imageType.equals(ImageType.THUMB) ? 7 : 11;
                    break;
                case LARGE:
                    numCols = imageType.equals(ImageType.BANNER) ? 3 : imageType.equals(ImageType.THUMB) ? 5 : 7;
                    break;
                case X_LARGE:
                    numCols = imageType.equals(ImageType.BANNER) ? 2 : imageType.equals(ImageType.THUMB) ? 3 : 5;
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + mPosterSizeSetting);
            }
            mVerticalColumnCount = numCols;
        } else if (mGridPresenter instanceof HorizontalGridPresenter) {
            int numRows;
            switch (posterSize) {
                case SMALLEST:
                    numRows = imageType.equals(ImageType.BANNER) ? 13 : imageType.equals(ImageType.THUMB) ? 7 : 5;
                    break;
                case SMALL:
                    numRows = imageType.equals(ImageType.BANNER) ? 11 : imageType.equals(ImageType.THUMB) ? 6 : 4;
                    break;
                case MED:
                    numRows = imageType.equals(ImageType.BANNER) ? 9 : imageType.equals(ImageType.THUMB) ? 5 : 3;
                    break;
                case LARGE:
                    numRows = imageType.equals(ImageType.BANNER) ? 7 : imageType.equals(ImageType.THUMB) ? 4 : 2;
                    break;
                case X_LARGE:
                    numRows = imageType.equals(ImageType.BANNER) ? 5 : imageType.equals(ImageType.THUMB) ? 2 : 1;
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + mPosterSizeSetting);
            }
            ((HorizontalGridPresenter) mGridPresenter).setNumberOfRows(numRows);
        }
    }

    private void setAutoCardGridValues() {
        if (mGridPresenter == null) {
            Timber.e("Invalid presenter, cannot calculate CardGridValues");
            return;
        }
        double cardScaling = Math.max(mCardFocusScale - 1.0, 0.0);
        int cardHeightInt = 100;
        int spacingHorizontalInt = 0;
        int spacingVerticalInt = 0;
        int paddingLeftInt = 0;
        int paddingTopInt = 0;
        int numRows = 0;
        int numCols = 0;
        int numCardsScreen = 0; // number of cards visible, including cutoff's

        if (mGridPresenter instanceof HorizontalGridPresenter) {
            numRows = ((HorizontalGridPresenter) mGridPresenter).getNumberOfRows();
            if (numRows == 1) { // reduce size so minimal cards are shown
                numRows = 0;
                numCols = MIN_NUM_CARDS;
            }
        } else if (mGridPresenter instanceof ComposeVerticalGridPresenter) {
            numCols = mVerticalColumnCount;
        }

        if (numRows > 0) {
            double paddingPct = cardScaling / numRows;
            double spacingPct = ((paddingPct / 2.0) * CARD_SPACING_PCT) * (numRows - 1);

            double wastedSpacePct = paddingPct + spacingPct;
            double usableCardSpace = mGridHeight / (1.0 + wastedSpacePct); // decrease size
            double cardHeight = usableCardSpace / numRows;

            // fix any rounding errors and make pixel perfect
            cardHeightInt = (int) Math.round(cardHeight);
            double cardPaddingTopBottomAdj = cardHeightInt * cardScaling;
            spacingVerticalInt = Math.max((int) (Math.round((cardPaddingTopBottomAdj / 2.0) * CARD_SPACING_PCT)), 0); // round spacing
            int paddingTopBottomInt = mGridHeight - ((cardHeightInt * numRows) + (spacingVerticalInt * (numRows - 1)));
            paddingTopInt = Math.max(paddingTopBottomInt / 2, 0);

            int sumSize = (cardHeightInt * numRows) + (spacingVerticalInt * (numRows - 1)) + (paddingTopInt * 2);
            if (Math.abs(sumSize - mGridHeight) > 2) {
                Timber.w("setAutoCardGridValues calculation delta > 2, something is off GridHeight <%s> sumSize <%s>!", mGridHeight, sumSize);
            }
            int cardWidthInt = (int) getCardWidthBy(cardHeightInt, mImageType, mFolder);
            paddingLeftInt = (int) Math.round((cardWidthInt * cardScaling) / 2.0);
            spacingHorizontalInt = Math.max((int) (Math.round(paddingLeftInt * CARD_SPACING_PCT)), 0); // round spacing
            if (mImageType == ImageType.BANNER) {
                spacingHorizontalInt = Math.max((int) (Math.round(paddingLeftInt * CARD_SPACING_HORIZONTAL_BANNER_PCT)), 0); // round spacing
            }
            int cardsCol = (int) Math.round(((double) mGridWidth / (cardWidthInt + spacingHorizontalInt)) + 0.5);
            mCardsScreenEst = numRows * cardsCol;
            mCardsScreenStride = numRows;
        } else if (numCols > 0) {
            double paddingPct = cardScaling / numCols;
            double spacingPct = ((paddingPct / 2.0) * CARD_SPACING_PCT) * (numCols - 1);
            if (mImageType == ImageType.BANNER) {
                spacingPct = ((paddingPct / 2.0) * CARD_SPACING_HORIZONTAL_BANNER_PCT) * (numCols - 1);
            }

            double wastedSpacePct = paddingPct + spacingPct;
            double usableCardSpace = mGridWidth / (1.0 + wastedSpacePct); // decrease size
            double cardWidth = usableCardSpace / numCols;

            // fix any rounding errors and make pixel perfect
            cardHeightInt = (int) Math.round(getCardHeightBy(cardWidth, mImageType, mFolder));
            int cardWidthInt = (int) getCardWidthBy(cardHeightInt, mImageType, mFolder);
            double cardPaddingLeftRightAdj = cardWidthInt * cardScaling;
            spacingHorizontalInt = Math.max((int) (Math.round((cardPaddingLeftRightAdj / 2.0) * CARD_SPACING_PCT)), 0); // round spacing
            if (mImageType == ImageType.BANNER) {
                spacingHorizontalInt = Math.max((int) (Math.round((cardPaddingLeftRightAdj / 2.0) * CARD_SPACING_HORIZONTAL_BANNER_PCT)), 0); // round spacing
            }
            int paddingLeftRightInt = mGridWidth - ((cardWidthInt * numCols) + (spacingHorizontalInt * (numCols - 1)));
            paddingLeftInt = Math.max(paddingLeftRightInt / 2, 0);

            int sumSize = (cardWidthInt * numCols) + (spacingHorizontalInt * (numCols - 1)) + (paddingLeftInt * 2);
            if (Math.abs(sumSize - mGridWidth) > 2) {
                Timber.w("setAutoCardGridValues calculation delta > 2, something is off GridWidth <%s> sumSize <%s>!", mGridWidth, sumSize);
            }
            paddingTopInt = (int) Math.round((cardHeightInt * cardScaling) / 2.0);
            spacingVerticalInt = Math.max((int) (Math.round(paddingTopInt * CARD_SPACING_PCT)), 0); // round spacing
            int cardsRow = (int) Math.round(((double) mGridHeight / (cardHeightInt + spacingVerticalInt)) + 0.5);
            mCardsScreenEst = numCols * cardsRow;
            mCardsScreenStride = numCols;
        }

        Timber.v("numCardsScreen <%s>", numCardsScreen);

        if (mCardHeight != cardHeightInt) {
            mDirty = true;
        }
        mCardHeight = cardHeightInt;
        mGridItemSpacingHorizontal = spacingHorizontalInt;
        mGridItemSpacingVertical = spacingVerticalInt;
        mGridPaddingLeft = paddingLeftInt;
        mGridPaddingTop = paddingTopInt;
    }

    private void setupQueries() {
        if (mFolder.getType() == BaseItemKind.USER_VIEW || mFolder.getType() == BaseItemKind.COLLECTION_FOLDER) {
            CollectionType type = mFolder.getCollectionType() != null ? mFolder.getCollectionType() : CollectionType.UNKNOWN;
            if (type == CollectionType.MUSIC) {
                //Special queries needed for album artists
                String includeType = getArguments().getString(Extras.IncludeType, null);
                if ("AlbumArtist".equals(includeType)) {
                    setRowDef(new BrowseRowDef("", BrowsingUtils.createAlbumArtistsRequest(mParentId), CHUNK_SIZE_MINIMUM, new ChangeTriggerType[]{}));
                    return;
                } else if ("Artist".equals(includeType)) {
                    setRowDef(new BrowseRowDef("", BrowsingUtils.createArtistsRequest(mParentId), CHUNK_SIZE_MINIMUM, new ChangeTriggerType[]{}));
                    return;
                }
            }
        }

        setRowDef(new BrowseRowDef("", BrowsingUtils.createBrowseGridItemsRequest(mFolder), CHUNK_SIZE_MINIMUM, false, true));
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!mPreferencesLoaded) {
            justLoaded = false;
            return;
        }

        PosterSize posterSizeSetting = libraryPreferences.get(LibraryPreferences.Companion.getPosterSize());
        ImageType imageType = libraryPreferences.get(LibraryPreferences.Companion.getImageType());
        GridDirection gridDirection = libraryPreferences.get(LibraryPreferences.Companion.getGridDirection());

        if (mImageType != imageType || mPosterSizeSetting != posterSizeSetting || mGridDirection != gridDirection || mDirty) {
            determiningPosterSize = true;

            mImageType = imageType;
            mPosterSizeSetting = posterSizeSetting;
            mGridDirection = gridDirection;

            if (mGridDirection.equals(GridDirection.VERTICAL) && (mGridPresenter == null || !(mGridPresenter instanceof ComposeVerticalGridPresenter))) {
                setGridPresenter(new ComposeVerticalGridPresenter());
            } else if (mGridDirection.equals(GridDirection.HORIZONTAL) && (mGridPresenter == null || !(mGridPresenter instanceof HorizontalGridPresenter))) {
                setGridPresenter(new HorizontalGridPresenter());
            }
            setDefaultGridRowCols(mPosterSizeSetting, mImageType);
            setAutoCardGridValues();
            configureNavigationControls();
            createGrid();
            loadGrid();
            addTools();
            determiningPosterSize = false;
        }

        if (!justLoaded) {
            //Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
            if (mAdapter != null) {
                mHandler.postDelayed(() -> {
                    if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                        return;

                    if (mAdapter != null && mAdapter.size() > 0) {
                        int selectedPosition = mSelectedPosition;
                        if (mAdapter.ReRetrieveIfNeeded()) {
                            if (selectedPosition >= 0) mPendingSelectedPosition = selectedPosition;
                        } else {
                            refreshCurrentItem();
                        }
                    }
                }, 500);
            }
        } else {
            justLoaded = false;
        }
    }

    private void buildAdapter() {
        mCardPresenter = new CardPresenter(false, mImageType, mCardHeight, true);
        mLastImagePrefetchPosition = -1;
        mLastImagePrefetchItemsLoaded = -1;
        mPendingSelectedPosition = -1;
        mPrefetchedImageUrls.clear();

        Timber.d("buildAdapter cardHeight <%s> getCardWidthBy <%s> chunks <%s> type <%s>", mCardHeight, (int) getCardWidthBy(mCardHeight, mImageType, mFolder), mRowDef.getChunkSize(), mRowDef.getQueryType().toString());

        // adapt chunk size if needed
        int chunkSize = mRowDef.getChunkSize();
        if (mCardsScreenEst > 0 && mCardsScreenEst >= chunkSize) {
            chunkSize = Math.min(mCardsScreenEst + mCardsScreenStride, 150); // cap at 150
            Timber.d("buildAdapter adjusting chunkSize to <%s> screenEst <%s>", chunkSize, mCardsScreenEst);
        }
        switch (mRowDef.getQueryType()) {
            case NextUp:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getNextUpQuery(), true, mCardPresenter, null);
                break;
            case Views:
                mAdapter = new ItemRowAdapter(requireContext(), GetUserViewsRequest.INSTANCE, mCardPresenter, null);
                break;
            case SimilarSeries:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getSimilarQuery(), QueryType.SimilarSeries, mCardPresenter, null);
                break;
            case SimilarMovies:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getSimilarQuery(), QueryType.SimilarMovies, mCardPresenter, null);
                break;
            case LiveTvChannel:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getTvChannelQuery(), 40, mCardPresenter, null);
                break;
            case LiveTvProgram:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getProgramQuery(), mCardPresenter, null, mRowDef.getLiveTvProgramSelectAction());
                break;
            case LiveTvRecording:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getRecordingQuery(), chunkSize, mCardPresenter, null);
                break;
            case Artists:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getArtistsQuery(), chunkSize, mCardPresenter, null);
                break;
            case AlbumArtists:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getAlbumArtistsQuery(), chunkSize, mCardPresenter, null);
                break;
            default:
                mAdapter = new ItemRowAdapter(requireContext(), mRowDef.getQuery(), chunkSize, mRowDef.getPreferParentThumb(), mRowDef.isStaticHeight(), mCardPresenter, null);
                break;
        }
        mDirty = false;

        FilterOptions filters = new FilterOptions();
        filters.setFavoriteOnly(libraryPreferences.get(LibraryPreferences.Companion.getFilterFavoritesOnly()));
        filters.setUnwatchedOnly(libraryPreferences.get(LibraryPreferences.Companion.getFilterUnwatchedOnly()));

        mAdapter.setRetrieveFinishedListener(new EmptyResponse(getLifecycle()) {
            @Override
            public void onResponse() {
                if (!isActive()) return;
                setStatusText(mFolder.getName());
                if (mCurrentItem == null) { // don't mess-up pos via loadMoreItemsIfNeeded
                    setItem(null);
                    updateCounter(mAdapter.getTotalItems() > 0 ? 1 : 0);
                }
                updateAlphabetPickerVisibility();
                if (mAdapter.getItemsLoaded() == 0) {
                    mGridView.setFocusable(false);
                    mHandler.postDelayed(() -> {
                        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                            return;

                        binding.title.setText(mFolder.getName());
                    }, 500);
                } else if (mGridView != null) {
                    mGridView.setFocusable(true);
                    applyPendingSelectedPosition();
                    keepGridFocused();
                }
            }
        });
        mAdapter.setFilters(filters);

        updateAdapter();
    }

    public void loadGrid() {
        if (mCardPresenter == null || mAdapter == null || mDirty) {
            buildAdapter();
        }

        mAdapter.setSortBy(getSortOption(libraryPreferences.get(LibraryPreferences.Companion.getSortBy())));
        if (mSelectedPosition >= 0) mPendingSelectedPosition = mSelectedPosition;
        mAdapter.Retrieve();
    }

    private ImageButton mSortButton;
    private ImageButton mSettingsButton;
    private ImageButton mUnwatchedButton;
    private ImageButton mFavoriteButton;
    private ViewGroup mActiveToolBar;

    private void updateDisplayPrefs() {
        CoroutineUtils.runOnLifecycle(getLifecycle(), (coroutineScope, continuation) -> {
            libraryPreferences.set(LibraryPreferences.Companion.getFilterFavoritesOnly(), mAdapter.getFilters().isFavoriteOnly());
            libraryPreferences.set(LibraryPreferences.Companion.getFilterUnwatchedOnly(), mAdapter.getFilters().isUnwatchedOnly());
            libraryPreferences.set(LibraryPreferences.Companion.getSortBy(), mAdapter.getSortBy());
            libraryPreferences.set(LibraryPreferences.Companion.getSortOrder(), getSortOption(mAdapter.getSortBy()).order);
            return libraryPreferences.commit(continuation);
        });
    }

    private void addTools() {
        binding.toolBar.removeAllViews();
        binding.verticalToolBar.removeAllViews();

        int size = Utils.convertDpToPixel(requireContext(), 26);
        int padding = Utils.convertDpToPixel(requireContext(), 3);

        mSortButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
        mSortButton.setImageResource(R.drawable.ic_sort);
        configureToolButton(mSortButton, size, padding);
        mSortButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Create sort menu
                PopupMenu sortMenu = new PopupMenu(getActivity(), mActiveToolBar, Gravity.END);
                for (Map.Entry<Integer, SortOption> entry : sortOptions.entrySet()) {
                    MenuItem item = sortMenu.getMenu().add(0, entry.getKey(), entry.getKey(), entry.getValue().name);
                    item.setChecked(entry.getValue().value.equals(libraryPreferences.get(LibraryPreferences.Companion.getSortBy())));
                }
                sortMenu.getMenu().setGroupCheckable(0, true, true);
                sortMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        mAdapter.setSortBy(Objects.requireNonNull(sortOptions.get(item.getItemId())));
                        mAdapter.Retrieve();
                        item.setChecked(true);
                        updateDisplayPrefs();
                        return true;
                    }
                });
                sortMenu.show();
            }
        });
        mSortButton.setContentDescription(getString(R.string.lbl_sort_by));

        mActiveToolBar.addView(mSortButton);

        if (mRowDef.getQueryType() == QueryType.Items) {
            mUnwatchedButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
            mUnwatchedButton.setImageResource(R.drawable.ic_unwatch);
            mUnwatchedButton.setActivated(mAdapter.getFilters().isUnwatchedOnly());
            configureToolButton(mUnwatchedButton, size, padding);
            mUnwatchedButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FilterOptions filters = mAdapter.getFilters();
                    if (filters == null) filters = new FilterOptions();

                    filters.setUnwatchedOnly(!filters.isUnwatchedOnly());
                    mUnwatchedButton.setActivated(filters.isUnwatchedOnly());
                    mAdapter.setFilters(filters);
                    mAdapter.Retrieve();
                    updateDisplayPrefs();
                }
            });
            mUnwatchedButton.setContentDescription(getString(R.string.lbl_unwatched));
            mActiveToolBar.addView(mUnwatchedButton);
        }

        mFavoriteButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
        mFavoriteButton.setImageResource(R.drawable.ic_heart);
        mFavoriteButton.setActivated(mAdapter.getFilters().isFavoriteOnly());
        configureToolButton(mFavoriteButton, size, padding);
        mFavoriteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FilterOptions filters = mAdapter.getFilters();
                if (filters == null) filters = new FilterOptions();

                filters.setFavoriteOnly(!filters.isFavoriteOnly());
                mFavoriteButton.setActivated(filters.isFavoriteOnly());
                mAdapter.setFilters(filters);
                mAdapter.Retrieve();
                updateDisplayPrefs();
            }
        });
        mFavoriteButton.setContentDescription(getString(R.string.lbl_favorite));
        mActiveToolBar.addView(mFavoriteButton);

        if (mFolder.getDisplayPreferencesId() != null) {
            MutableStateFlow<Boolean> settingsVisible = BrowseGridFragmentHelperKt.createSettingsVisibility(BrowseGridFragment.this);
            mSettingsButton = new ImageButton(requireContext(), null, 0, R.style.Button_Icon);
            mSettingsButton.setImageResource(R.drawable.ic_settings);
            configureToolButton(mSettingsButton, size, padding);
            mSettingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    settingsVisible.setValue(true);
                }
            });
            mSettingsButton.setContentDescription(getString(R.string.lbl_settings));
            mActiveToolBar.addView(mSettingsButton);
            BrowseGridFragmentHelperKt.addSettings(BrowseGridFragment.this, binding.settings, mFolder.getId(), mFolder.getDisplayPreferencesId(), settingsVisible);
        }

        configureNavigationControlsFocus();
    }

    private void configureToolButton(ImageButton button, int size, int padding) {
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        button.setPadding(padding, padding, padding, padding);
        button.setAdjustViewBounds(true);
    }

    private void setupEventListeners() {
        if (mGridPresenter != null) {
            if (mGridPresenter instanceof HorizontalGridPresenter)
                ((HorizontalGridPresenter) mGridPresenter).setOnItemViewClickedListener(mClickedListener);
            else if (mGridPresenter instanceof ComposeVerticalGridPresenter)
                ((ComposeVerticalGridPresenter) mGridPresenter).setOnItemViewClickedListener(mClickedListener);
        }
        mClickedListener.registerListener(new ItemViewClickedListener());
        mSelectedListener.registerListener(new ItemViewSelectedListener());

        CoroutineUtils.readCustomMessagesOnLifecycle(getLifecycle(), customMessageRepository.getValue(), message -> {
            if (message.equals(CustomMessage.RefreshCurrentItem.INSTANCE)) refreshCurrentItem();
            return null;
        });
    }

    private void refreshCurrentItem() {
        if (mCurrentItem == null) return;
        Timber.d("Refresh item \"%s\"", mCurrentItem.getFullName(requireContext()));
        ItemRowAdapterHelperKt.refreshItem(mAdapter, api.getValue(), this, mCurrentItem, () -> {
            //Now - if filtered make sure we still pass
            if (mAdapter.getFilters() == null) return null;
            if ((mAdapter.getFilters().isFavoriteOnly() && !mCurrentItem.isFavorite()) || (mAdapter.getFilters().isUnwatchedOnly() && mCurrentItem.isPlayed())) {
                // if we are about to remove the current item, throw focus to toolbar so framework doesn't crash
                mActiveToolBar.requestFocus();
                mAdapter.remove(mCurrentItem);
                mAdapter.setTotalItems(mAdapter.getTotalItems() - 1);
                updateCounter(mAdapter.indexOf(mCurrentItem));
            }
            return null;
        });
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(final Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {

            if (!(item instanceof BaseRowItem)) return;
            itemLauncher.getValue().launch((BaseRowItem) item, mAdapter, requireContext());
        }
    }

    private final Runnable mDelayedSetItem = new Runnable() {
        @Override
        public void run() {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;

            backgroundService.getValue().setBackground(mCurrentItem.getBaseItem());
            setItem(mCurrentItem);
        }
    };

    private final class ItemViewSelectedListener implements OnItemViewSelectedListener {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {
            mHandler.removeCallbacks(mDelayedSetItem);
            if (!(item instanceof BaseRowItem)) {
                mCurrentItem = null;
                binding.title.setText(mainTitle);
                //fill in default background
                backgroundService.getValue().clearBackgrounds();
            } else {
                mCurrentItem = (BaseRowItem) item;
                binding.title.setText(mCurrentItem.getName(requireContext()));
                binding.infoRow.removeAllViews();
                mHandler.postDelayed(mDelayedSetItem, VIEW_SELECT_UPDATE_DELAY);

                int position = mAdapter.indexOf(mCurrentItem);
                if (!determiningPosterSize)
                    mAdapter.loadMoreItemsIfNeeded(position);
                prefetchCardImages(position);
            }
        }
    }
}
