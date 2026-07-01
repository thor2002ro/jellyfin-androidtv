package org.jellyfin.androidtv.ui.playback;

import static org.koin.java.KoinJavaComponent.inject;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.lifecycle.Lifecycle;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.CustomMessage;
import org.jellyfin.androidtv.data.repository.CustomMessageRepository;
import org.jellyfin.androidtv.data.service.BackgroundService;
import org.jellyfin.androidtv.databinding.OverlayTvGuideBinding;
import org.jellyfin.androidtv.databinding.VlcPlayerInterfaceBinding;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.UserSettingPreferences;
import org.jellyfin.androidtv.ui.GuideChannelHeader;
import org.jellyfin.androidtv.ui.GuidePagingButton;
import org.jellyfin.androidtv.ui.HorizontalScrollViewListener;
import org.jellyfin.androidtv.ui.LiveProgramDetailPopup;
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView;
import org.jellyfin.androidtv.ui.ObservableScrollView;
import org.jellyfin.androidtv.ui.ProgramGridCell;
import org.jellyfin.androidtv.ui.ScrollViewListener;
import org.jellyfin.androidtv.ui.itemhandling.ChapterItemInfoBaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragmentHelperKt;
import org.jellyfin.androidtv.ui.livetv.TvManager;
import org.jellyfin.androidtv.ui.navigation.Destinations;
import org.jellyfin.androidtv.ui.navigation.NavigationRepository;
import org.jellyfin.androidtv.ui.playback.overlay.LeanbackOverlayFragment;
import org.jellyfin.androidtv.ui.playback.overlay.action.StreamStatusBuilder;
import org.jellyfin.androidtv.ui.presentation.CardPresenter;
import org.jellyfin.androidtv.ui.presentation.ChannelCardPresenter;
import org.jellyfin.androidtv.ui.presentation.CircularObjectAdapter;
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter;
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter;
import org.jellyfin.androidtv.util.CoroutineUtils;
import org.jellyfin.androidtv.util.DateTimeExtensionsKt;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.KeyEventExtensionsKt;
import org.jellyfin.androidtv.util.LanguageUtils;
import org.jellyfin.androidtv.util.PlaybackHelper;
import org.jellyfin.androidtv.util.TextUtilsKt;
import org.jellyfin.androidtv.util.TimeUtils;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.EmptyResponse;
import org.jellyfin.androidtv.util.apiclient.Response;
import org.jellyfin.androidtv.util.sdk.BaseItemExtensionsKt;
import org.jellyfin.sdk.model.api.MediaSourceInfo;
import org.jellyfin.sdk.model.api.MediaStream;
import org.jellyfin.sdk.model.api.MediaStreamType;
import org.jellyfin.sdk.model.api.PlayMethod;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.api.ChapterInfo;
import org.jellyfin.sdk.model.api.TranscodingInfo;
import org.jetbrains.annotations.NotNull;

import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Lazy;
import timber.log.Timber;

public class CustomPlaybackOverlayFragment extends Fragment implements LiveTvGuide, View.OnKeyListener {
    protected VlcPlayerInterfaceBinding binding;
    private OverlayTvGuideBinding tvGuideBinding;

    private RowsSupportFragment mPopupRowsFragment;
    private ListRow mChapterRow;
    private ArrayObjectAdapter mPopupRowAdapter;
    private PositionableListRowPresenter mPopupRowPresenter;
    private CircularObjectAdapter mCircularChannelAdapter;
    private CircularObjectAdapter mCircularChapterAdapter;
    private Runnable mProgramInfoUpdateTask;
    private boolean mQuickChannelChangerVisible = false;

    private static final int OVERLAY_GUIDE_TEXT_DEBOUNCE_MS = 200;
    private static final long TICKS_PER_MS = 10_000;
    private static final long TRANSCODING_STATUS_REFRESH_MS = 3_000;

    //Live guide items
    private static final int PAGE_SIZE = 75;
    private static final int AUTO_LOAD_CHANNEL_THRESHOLD = 8;
    private static final int GUIDE_HOURS = 9;

    BaseItemDto mSelectedProgram;
    RelativeLayout mSelectedProgramView;
    private boolean mGuideVisible = false;
    private LocalDateTime mCurrentGuideStart;
    private LocalDateTime mCurrentGuideEnd;
    private int mCurrentDisplayChannelStartNdx = 0;
    private int mCurrentDisplayChannelEndNdx = 0;
    private List<BaseItemDto> mAllChannels;
    private UUID mFirstFocusChannelId;
    private boolean mPreserveGuideFocusOnNextLoad = false;
    private boolean mIgnoreNextGuideFocusAutoLoad = false;
    private int mGuideLoadRequestId = 0;
    private int mProgramLoadRequestId = 0;
    private int guideRowHeightPx;
    private int guideVisibleRows;

    private List<BaseItemDto> mItemsToPlay;

    private Animation fadeOut;
    private Animation slideUp;
    private Animation slideDown;
    private Animation showPopup;
    private Animation hidePopup;
    private final Handler mHandler = new Handler();
    private Runnable mHideTask;

    private AudioManager mAudioManager;

    private boolean mFadeEnabled = false;
    private boolean mIsVisible = false;
    private boolean mPopupPanelVisible = false;
    private boolean mStreamStatusOverlayVisible = false;
    private boolean navigating = false;
    private LocalDateTime mProgramEndTime = null;
    private boolean mPendingSeekConfirmation = false;
    private boolean mCenterShortcutArmed = false;
    private boolean mCenterLongPressHandled = false;
    private Integer mSkipOverlayKeyCode = null;
    private Integer mHeldSeekKeyCode = null;
    private final ExecutorService mTranscodingStatusExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean mTranscodingStatusFetchInFlight = false;
    private long mLastTranscodingStatusFetchMs = 0;
    private String mTranscodingStatusKey = null;
    private TranscodingInfo mTranscodingInfo = null;

    protected LeanbackOverlayFragment leanbackOverlayFragment;

    private final Lazy<org.jellyfin.sdk.api.client.ApiClient> api = inject(org.jellyfin.sdk.api.client.ApiClient.class);
    private final Lazy<MediaManager> mediaManager = inject(MediaManager.class);
    private final Lazy<VideoQueueManager> videoQueueManager = inject(VideoQueueManager.class);
    private final Lazy<PlaybackControllerContainer> playbackControllerContainer = inject(PlaybackControllerContainer.class);
    private final Lazy<CustomMessageRepository> customMessageRepository = inject(CustomMessageRepository.class);
    private final Lazy<NavigationRepository> navigationRepository = inject(NavigationRepository.class);
    private final Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    private final Lazy<ImageHelper> imageHelper = inject(ImageHelper.class);
    private final Lazy<UserPreferences> userPreferences = inject(UserPreferences.class);
    private final Lazy<UserSettingPreferences> userSettingPreferences = inject(UserSettingPreferences.class);
    private final Lazy<TranscodingStatusRepository> transcodingStatusRepository = inject(TranscodingStatusRepository.class);

    private final PlaybackOverlayFragmentHelper helper = new PlaybackOverlayFragmentHelper(this);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // stop any audio that may be playing
        mediaManager.getValue().stopAudio(true);

        mAudioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        if (mAudioManager == null) {
            Timber.e("Unable to get audio manager");
            Utils.showToast(requireContext(), R.string.msg_cannot_play_time);
            return;
        }

        requireActivity().setVolumeControlStream(AudioManager.STREAM_MUSIC);

        mItemsToPlay = videoQueueManager.getValue().getCurrentVideoQueue();
        if (mItemsToPlay == null || mItemsToPlay.isEmpty()) return;

        int mediaPosition = videoQueueManager.getValue().getCurrentMediaPosition();

        playbackControllerContainer.getValue().setPlaybackController(new PlaybackController(mItemsToPlay, this, mediaPosition));

        // setup fade task
        mHideTask = () -> {
            leanbackOverlayFragment.getPlayerGlue().hideThumbnailPreview();
            if (mIsVisible) {
                leanbackOverlayFragment.hideOverlay();
            }
        };

        backgroundService.getValue().disable();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = VlcPlayerInterfaceBinding.inflate(inflater, container, false);
        binding.textClock.setVideoPlayer(true);

        // inject the RowsSupportFragment in the popup container
        if (getChildFragmentManager().findFragmentById(R.id.rows_area) == null) {
            mPopupRowsFragment = new RowsSupportFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.rows_area, mPopupRowsFragment).commit();
        } else {
            mPopupRowsFragment = (RowsSupportFragment) getChildFragmentManager()
                    .findFragmentById(R.id.rows_area);
        }

        mPopupRowPresenter = new PositionableListRowPresenter(null, /* trapFocus */ true);
        mPopupRowAdapter = new ArrayObjectAdapter(mPopupRowPresenter);
        mPopupRowsFragment.setAdapter(mPopupRowAdapter);
        mPopupRowsFragment.setOnItemViewClickedListener(itemViewClickedListener);
        mPopupRowsFragment.setOnItemViewSelectedListener((itemViewHolder, item, rowViewHolder, row) -> {
            if (!mQuickChannelChangerVisible) return;

            if (mProgramInfoUpdateTask != null) {
                mHandler.removeCallbacks(mProgramInfoUpdateTask);
            }
            binding.popupDescription.setText("");
            binding.popupHeader.setText("");

            if (item instanceof BaseItemDto) {
                BaseItemDto channel = (BaseItemDto) item;
                BaseItemDto program = channel.getCurrentProgram();
                String overview = (program != null) ? program.getOverview() : null;
                String headerText = getProgramHeaderText(program);

                mProgramInfoUpdateTask = () -> {
                    if (binding == null) return;
                    binding.popupHeader.setText(headerText);
                    binding.popupDescription.setText(overview != null ? overview : "");
                };
                mHandler.postDelayed(mProgramInfoUpdateTask, OVERLAY_GUIDE_TEXT_DEBOUNCE_MS);
            }
        });

        // And the Live Guide element
        tvGuideBinding = OverlayTvGuideBinding.inflate(inflater, container, false);
        binding.getRoot().addView(tvGuideBinding.getRoot());
        tvGuideBinding.getRoot().setVisibility(View.GONE);

        binding.getRoot().setOnTouchListener((v, event) -> {
            //and then manage our fade timer
            if (mFadeEnabled) startFadeTimer();

            Timber.v("Got touch event.");
            return false;
        });
        binding.skipOverlay.setOnSkipClickListener(() -> {
            consumeSkipOverlay();
        });
        binding.skipOverlay.setOnKeyListener((v, keyCode, event) -> handleSkipOverlayKey(keyCode, event));
        updateSkipOverlayHitTarget();

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (mItemsToPlay == null || mItemsToPlay.isEmpty()) {
            Utils.showToast(requireContext(), getString(R.string.msg_no_playable_items));
            closePlayer();
            return;
        }

        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();

        if (playbackController != null) {
            playbackController.init(new VideoManager(requireActivity(), view, helper), this);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (mProgramInfoUpdateTask != null) {
            mHandler.removeCallbacks(mProgramInfoUpdateTask);
            mProgramInfoUpdateTask = null;
        }
        binding = null;
        mStreamStatusOverlayVisible = false;
        // To fix race condition in hide timer
        mIsVisible = false;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (mItemsToPlay == null || mItemsToPlay.isEmpty()) return;

        prepareOverlayFragment();

        //pre-load animations
        fadeOut = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out);
        fadeOut.setAnimationListener(hideAnimationListener);
        slideDown = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_top_in);
        slideUp = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_bottom_in);
        slideDown.setAnimationListener(showAnimationListener);
        setupPopupAnimations();

        //live guide
        tvGuideBinding.channelsStatus.setTextColor(Color.GRAY);
        tvGuideBinding.filterStatus.setTextColor(Color.GRAY);

        tvGuideBinding.programRows.setFocusable(false);
        tvGuideBinding.programVScroller.setScrollViewListener(new ScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableScrollView scrollView, int x, int y, int oldx, int oldy) {
                tvGuideBinding.channelScroller.scrollTo(x, y);
            }
        });

        tvGuideBinding.channelScroller.setScrollViewListener(new ScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableScrollView scrollView, int x, int y, int oldx, int oldy) {
                tvGuideBinding.programVScroller.scrollTo(x, y);
            }
        });

        tvGuideBinding.timelineHScroller.setFocusable(false);
        tvGuideBinding.timelineHScroller.setFocusableInTouchMode(false);
        tvGuideBinding.timeline.setFocusable(false);
        tvGuideBinding.timeline.setFocusableInTouchMode(false);
        tvGuideBinding.channelScroller.setFocusable(false);
        tvGuideBinding.channelScroller.setFocusableInTouchMode(false);

        tvGuideBinding.programHScroller.setScrollViewListener(new HorizontalScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableHorizontalScrollView scrollView, int x, int y, int oldx, int oldy) {
                tvGuideBinding.timelineHScroller.scrollTo(x, y);
            }
        });
        tvGuideBinding.programHScroller.setFocusable(false);
        tvGuideBinding.programHScroller.setFocusableInTouchMode(false);

        tvGuideBinding.channels.setFocusable(false);
        tvGuideBinding.channelScroller.setFocusable(false);

        // register to receive message from popup
        CoroutineUtils.readCustomMessagesOnLifecycle(getLifecycle(), customMessageRepository.getValue(), message -> {
            if (message.equals(CustomMessage.ActionComplete.INSTANCE)) dismissProgramOptions();
            return null;
        });

        int startPos = getArguments().getInt("Position", 0);

        // start playing
        playbackControllerContainer.getValue().getPlaybackController().play(startPos);
        leanbackOverlayFragment.updatePlayState();

        // Set initial skip overlay state
        updateSkipOverlayAvailability();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        requireActivity().getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
    }

    private void prepareOverlayFragment() {
        leanbackOverlayFragment = (LeanbackOverlayFragment) getChildFragmentManager().findFragmentById(R.id.leanback_fragment);
        if (leanbackOverlayFragment != null) {
            leanbackOverlayFragment.initFromView(this);
            leanbackOverlayFragment.mediaInfoChanged();
            leanbackOverlayFragment.setOnKeyInterceptListener(keyListener);
        }
    }

    private void setupPopupAnimations() {
        showPopup = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_bottom_in);
        showPopup.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                binding.popupArea.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                binding.popupArea.requestFocus();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        hidePopup = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out);
        hidePopup.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                if (binding == null || mPopupPanelVisible) return;
                binding.popupHeader.setVisibility(View.GONE);
                binding.popupHeader.setText("");
                binding.popupDescription.setVisibility(View.GONE);
                binding.popupDescription.setText("");
                binding.popupArea.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
    }

    private AudioManager.OnAudioFocusChangeListener mAudioFocusChanged = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    playbackControllerContainer.getValue().getPlaybackController().pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    // We don't do anything here on purpose
                    // On the Nexus we get this notification erroneously when first starting up
                    // and in any instance that we navigate away from our page, we already handle
                    // stopping video and handing back audio focus
                    break;
            }
        }
    };

    private OnItemViewClickedListener itemViewClickedListener = new OnItemViewClickedListener() {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof ChapterItemInfoBaseRowItem) {
                ChapterItemInfoBaseRowItem rowItem = (ChapterItemInfoBaseRowItem) item;
                Long start = rowItem.getChapterInfo().getStartPositionTicks() / TICKS_PER_MS;
                playbackControllerContainer.getValue().getPlaybackController().seek(start);
                hidePopupPanel();
            } else if (item instanceof BaseItemDto) {
                hidePopupPanel();
                switchChannel(((BaseItemDto) item).getId());
            }
        }
    };

    private OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (mStreamStatusOverlayVisible) {
                setStreamStatusOverlayVisible(false);
            } else if (mPopupPanelVisible) {
                // back should just hide the popup panel
                hidePopupPanel();
                leanbackOverlayFragment.hideOverlay();

                // also close this if live tv
                if (playbackControllerContainer.getValue().getPlaybackController().isLiveTv()) hide();
            } else if (mGuideVisible) {
                hideGuide();
            } else {
                closePlayer();
            }
        }
    };

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (handleSkipOverlayKey(keyCode, event)) return true;
        if (handleDpadSeekKey(keyCode, event)) return true;

        if (event.isLongPress()) {
            if (isCenterKey(keyCode)) {
                if (mSelectedProgramView instanceof ProgramGridCell) {
                    showProgramOptions();
                    return true;
                } else if (mSelectedProgramView instanceof GuideChannelHeader) {
                    CustomPlaybackOverlayFragmentHelperKt.toggleFavorite(this);
                    return true;
                }

                return mCenterShortcutArmed;
            }
        } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (isCenterKey(keyCode)) {
                if (shouldTrackGuideCenterKey()) {
                    event.startTracking();
                    return true;
                }

                if (canUsePlaybackCenterShortcut()) {
                    mCenterShortcutArmed = true;
                    mCenterLongPressHandled = false;
                    mHandler.removeCallbacks(mCenterLongPressTask);
                    mHandler.postDelayed(mCenterLongPressTask, ViewConfiguration.getLongPressTimeout());
                    return true;
                }

                return false;
            }
            if (mGuideVisible && KeyEventExtensionsKt.isPageKey(keyCode)) {
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (isCenterKey(keyCode)) {
                if (mCenterShortcutArmed) {
                    mHandler.removeCallbacks(mCenterLongPressTask);
                    if (!mCenterLongPressHandled) handleCenterPlaybackShortcut();
                    clearCenterShortcut();
                    return true;
                }

                if ((event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                    if (mGuideVisible && mSelectedProgramView instanceof ProgramGridCell && mSelectedProgram != null && mSelectedProgram.getChannelId() != null) {
                        if (mSelectedProgram.getStartDate().isBefore(LocalDateTime.now()))
                            switchChannel(mSelectedProgram.getChannelId());
                        else
                            showProgramOptions();
                        return true;
                    } else if (mSelectedProgramView instanceof GuideChannelHeader) {
                        switchChannel(((GuideChannelHeader) mSelectedProgramView).getChannel().getId(), false);
                        return true;
                    }
                }
                return false;
            }

            if (keyListener.onKey(v, keyCode, event)) return true;

            if (handleGuideChannelPageKey(keyCode)) return true;

            PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();

            if (playbackController != null) {
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                    playbackController.play(0);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    playbackController.pause();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    playbackController.playPause();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                    playbackController.fastForward();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                    playbackController.rewind();
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isCenterKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private boolean isDpadSeekKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    public void updateSkipOverlayAvailability() {
        if (binding == null) return;

        binding.skipOverlay.setSkipUiEnabled(!mGuideVisible && !mPopupPanelVisible);
        binding.skipOverlay.setPlayerUiVisible(mIsVisible);
        binding.skipOverlay.bringToFront();
        updateSkipOverlayHitTarget();
    }

    private void updateSkipOverlayHitTarget() {
        if (binding == null) return;

        boolean visible = binding.skipOverlay.getVisible();
        boolean autoSelected = isSkipOverlayAutoSelected();

        binding.skipOverlay.setClickable(visible);
        binding.skipOverlay.setFocusable(autoSelected);
        binding.skipOverlay.setFocusableInTouchMode(autoSelected);

        if (autoSelected && !binding.skipOverlay.hasFocus()) {
            binding.skipOverlay.requestFocus();
        }
    }

    public boolean isSkipOverlayAutoSelected() {
        return binding != null &&
                binding.skipOverlay.getVisible() &&
                !mIsVisible &&
                !mGuideVisible &&
                !mPopupPanelVisible &&
                !mQuickChannelChangerVisible &&
                !mPendingSeekConfirmation;
    }

    private boolean handleSkipOverlayKey(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && mSkipOverlayKeyCode != null && mSkipOverlayKeyCode == keyCode) {
            mSkipOverlayKeyCode = null;
            return true;
        }

        if (binding == null || !binding.skipOverlay.getVisible()) return false;

        boolean isDismissKey = keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                keyCode == KeyEvent.KEYCODE_ESCAPE;
        boolean isConfirmKey = isCenterKey(keyCode);

        if (!isDismissKey && !isConfirmKey) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        mSkipOverlayKeyCode = keyCode;

        if (isDismissKey) {
            clearSkipOverlay();
            return true;
        }

        consumeSkipOverlay();
        return true;
    }

    private boolean handleDpadSeekKey(int keyCode, KeyEvent event) {
        if (!isDpadSeekKey(keyCode)) return false;

        if (event.getAction() == KeyEvent.ACTION_UP && mHeldSeekKeyCode != null && mHeldSeekKeyCode == keyCode) {
            mHeldSeekKeyCode = null;
            return true;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        if (playbackController == null || playbackController.isLiveTv()) return false;
        if (mGuideVisible || mPopupPanelVisible || mPendingSeekConfirmation && !userPreferences.getValue().get(UserPreferences.Companion.getSeekConfirmationRequired())) {
            return false;
        }

		boolean continuingHeldSeek = mHeldSeekKeyCode != null && mHeldSeekKeyCode == keyCode;
		if (!continuingHeldSeek && mIsVisible && !isSeekBarFocused()) return false;

		mHeldSeekKeyCode = keyCode;
		boolean keepPlayerUiHidden = !mIsVisible;
		if (keepPlayerUiHidden) {
			leanbackOverlayFragment.setShouldShowOverlay(false);
			leanbackOverlayFragment.hideOverlay();
			hide();
		}

        boolean forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean seekConfirmationRequired = userPreferences.getValue().get(UserPreferences.Companion.getSeekConfirmationRequired());
        if (keepPlayerUiHidden) {
            if (forward) {
                playbackController.fastForward();
            } else {
                playbackController.rewind();
            }
        } else if (seekConfirmationRequired) {
            if (!mPendingSeekConfirmation) {
                enterSeekConfirmationMode();
            }

            long skipAmount = userSettingPreferences.getValue().get(
                    forward
                            ? UserSettingPreferences.Companion.getSkipForwardLength()
                            : UserSettingPreferences.Companion.getSkipBackLength()
            ).longValue();
            leanbackOverlayFragment.getPlayerGlue().previewSeek(forward ? skipAmount : -skipAmount);
        } else if (forward) {
            leanbackOverlayFragment.getPlayerGlue().fastForward();
        } else {
            leanbackOverlayFragment.getPlayerGlue().rewind();
        }

		if (keepPlayerUiHidden) {
			leanbackOverlayFragment.setShouldShowOverlay(false);
			leanbackOverlayFragment.hideOverlay();
			hide();
		} else {
			setFadingEnabled(true);
		}
		return true;
	}

    public boolean consumeSkipOverlay() {
        if (binding == null || !binding.skipOverlay.getVisible()) return false;

        Long targetPosition = binding.skipOverlay.getTargetPositionMs();
        if (targetPosition == null) {
            clearSkipOverlay();
            return true;
        }

        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        if (playbackController == null) return true;

        playbackController.seek(targetPosition, true);
        if (leanbackOverlayFragment != null) leanbackOverlayFragment.setShouldShowOverlay(false);
        clearSkipOverlay();
        return true;
    }

    public boolean consumeAutoSelectedSkipOverlay() {
        return isSkipOverlayAutoSelected() && consumeSkipOverlay();
    }

    private boolean canUsePlaybackCenterShortcut() {
        if (binding == null) return false;
        if (mGuideVisible || mPopupPanelVisible || mQuickChannelChangerVisible || mPendingSeekConfirmation) return false;
        if (binding.skipOverlay.getVisible()) return false;
        return !isPlayerUiVisible();
    }

    private boolean shouldTrackGuideCenterKey() {
        return mGuideVisible ||
                mSelectedProgramView instanceof ProgramGridCell ||
                mSelectedProgramView instanceof GuideChannelHeader;
    }

    private boolean isPlayerUiVisible() {
        return mIsVisible;
    }

    private boolean showStreamStatusOverlayShortcut() {
        if (!canUsePlaybackCenterShortcut()) return false;
        toggleStreamStatusOverlay();
        return true;
    }

    private final Runnable mCenterLongPressTask = () -> {
        if (!mCenterShortcutArmed || !canUsePlaybackCenterShortcut()) return;

        mCenterLongPressHandled = true;
        toggleStreamStatusOverlay();
    };

    private void clearCenterShortcut() {
        mHandler.removeCallbacks(mCenterLongPressTask);
        mCenterShortcutArmed = false;
        mCenterLongPressHandled = false;
    }

    private boolean handleCenterPlaybackShortcut() {
        if (!canUsePlaybackCenterShortcut()) return false;

        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        if (playbackController == null) return false;

        playbackController.playPause();
        if (playbackController.isPaused()) {
            show();
            setFadingEnabled(false);
        }
        return true;
    }

    private boolean isPlaybackPaused() {
        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        return playbackController != null && playbackController.isPaused();
    }

    private boolean handleGuideChannelPageKey(int keyCode) {
        if (!mGuideVisible) return false;
        if (!KeyEventExtensionsKt.isPageKey(keyCode)) return false;
        if (mDetailPopup != null && mDetailPopup.isShowing()) {
            return true;
        }
        if (tvGuideBinding == null || tvGuideBinding.spinner.getVisibility() == View.VISIBLE || mAllChannels == null || mAllChannels.isEmpty()) {
            return true;
        }

        if (guideVisibleRows == 0) {
            guideRowHeightPx = Utils.convertDpToPixel(requireContext(), LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP);
            guideVisibleRows = Math.max(1, tvGuideBinding.channelScroller.getHeight() / guideRowHeightPx);
        }
        LiveTvGuideFragmentHelperKt.pageGuideChannels(
                requireActivity(), tvGuideBinding.programRows, tvGuideBinding.channels, guideVisibleRows,
                KeyEventExtensionsKt.isPageForward(keyCode)
        );
        return true;
    }

    @Override
    public void refreshFavorite(UUID channelId, boolean isFavorite) {
        for (int i = 0; i < tvGuideBinding.channels.getChildCount(); i++) {
            View child = tvGuideBinding.channels.getChildAt(i);
            if (!(child instanceof GuideChannelHeader)) continue;

            GuideChannelHeader gch = (GuideChannelHeader) child;
            if (gch.getChannel().getId().equals(channelId)) {
                BaseItemDto channel = TvManager.getChannelByID(channelId);
                if (channel != null) {
                    gch.setChannel(channel);
                }
                gch.setFavorite(isFavorite);
                break;
            }
        }
    }

    private View.OnKeyListener keyListener = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (handleSkipOverlayKey(keyCode, event)) return true;
            if (handleDpadSeekKey(keyCode, event)) return true;

            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (!mGuideVisible && !mPopupPanelVisible)
                    leanbackOverlayFragment.setShouldShowOverlay(true);
                else {
                    leanbackOverlayFragment.setShouldShowOverlay(false);
                    leanbackOverlayFragment.hideOverlay();
                }

                if (mPendingSeekConfirmation) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        applyPendingSeek();
                        return true;
                    }

                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                            keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        exitSeekConfirmationMode();
                        return true;
                    }
                }

                if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                    closePlayer();
                    return true;
                }

                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (mStreamStatusOverlayVisible) {
                        setStreamStatusOverlayVisible(false);
                        return true;
                    } else if (mPopupPanelVisible) {
                        // back should just hide the popup panel
                        hidePopupPanel();
                        leanbackOverlayFragment.hideOverlay();

                        // also close this if live tv
                        if (playbackControllerContainer.getValue().getPlaybackController().isLiveTv()) hide();
                        return true;
                    } else if (mGuideVisible) {
                        hideGuide();
                        return true;
                    }
                }

                if (playbackControllerContainer.getValue().getPlaybackController().isLiveTv() && !mPopupPanelVisible && !mGuideVisible && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!leanbackOverlayFragment.isControlsOverlayVisible()) {
                        leanbackOverlayFragment.setShouldShowOverlay(false);
                        leanbackOverlayFragment.hideOverlay();
                        showQuickChannelChanger();
                        return true;
                    }
                }

                if (mGuideVisible) {
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        // go back to normal
                        hideGuide();
                        return true;
                    } else if ((keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) &&
                            (mSelectedProgram != null && mSelectedProgram.getChannelId() != null)) {
                        // tune to the current channel
                        switchChannel(mSelectedProgram.getChannelId());
                        return true;
                    } else {
                        return true;
                    }
                }

                if (playbackControllerContainer.getValue().getPlaybackController().isLiveTv() && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y)) {
                    showGuide();
                    return true;
                }

                if (mIsVisible && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                    //back should just hide the panel
                    hide();
                    return true;
                }

                if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_BUTTON_B && keyCode != KeyEvent.KEYCODE_ESCAPE) {
                    if (mPopupPanelVisible) {
                        // up or down should close panel
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            hidePopupPanel();
                            if (playbackControllerContainer.getValue().getPlaybackController().isLiveTv())
                                hide(); //also close this if live tv
                            return true;
                        } else {
                            return false;
                        }
                    }

                    // Control fast forward and rewind if overlay hidden and not showing live TV
                    if (!playbackControllerContainer.getValue().getPlaybackController().isLiveTv()) {
                        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                            playbackControllerContainer.getValue().getPlaybackController().fastForward();
                            setFadingEnabled(true);
                            return true;
                        }

                        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                            playbackControllerContainer.getValue().getPlaybackController().rewind();
                            setFadingEnabled(true);
                            return true;
                        }
                    }

                    if ((!mIsVisible || isSeekBarFocused()) && !playbackControllerContainer.getValue().getPlaybackController().isLiveTv()) {
                        boolean seekConfirmationRequired = userPreferences.getValue().get(UserPreferences.Companion.getSeekConfirmationRequired());

                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            if (seekConfirmationRequired) {
                                if (!mPendingSeekConfirmation) {
                                    enterSeekConfirmationMode();
                                }

                                long skipAmount = userSettingPreferences.getValue().get(UserSettingPreferences.Companion.getSkipForwardLength()).longValue();
                                leanbackOverlayFragment.getPlayerGlue().previewSeek(skipAmount);
                                setFadingEnabled(true);
                                return true;
                            }

                            leanbackOverlayFragment.getPlayerGlue().fastForward();
                            setFadingEnabled(true);
                            return true;
                        }

                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            if (seekConfirmationRequired) {
                                if (!mPendingSeekConfirmation) {
                                    enterSeekConfirmationMode();
                                }

                                long skipAmount = userSettingPreferences.getValue().get(UserSettingPreferences.Companion.getSkipBackLength()).longValue();
                                leanbackOverlayFragment.getPlayerGlue().previewSeek(-skipAmount);
                                setFadingEnabled(true);
                                return true;
                            }

                            leanbackOverlayFragment.getPlayerGlue().rewind();
                            setFadingEnabled(true);
                            return true;
                        }
                    }

                    if (!mIsVisible) {
                        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                                && playbackControllerContainer.getValue().getPlaybackController().canSeek()) {
                            // if the player is playing and the overlay is hidden, this will pause
                            // if the player is paused and then 'back' is pressed to hide the overlay, this will play
                            playbackControllerContainer.getValue().getPlaybackController().playPause();
                            return true;
                        }
                    }

                    //and then manage our fade timer
                    if (mFadeEnabled) startFadeTimer();
                }
                if (playbackControllerContainer.getValue().getPlaybackController().isLiveTv() && !mGuideVisible && keyCode != KeyEvent.KEYCODE_BACK) {
                    // Using the remote keypress that brings up the overlay fragment as a trigger. This check will go fetch the currently playing item
                    // and get its end time, then update the overlay fragment with the new end time.
                    BaseItemDto current = playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem();
                    if (current != null && current.getCurrentProgram() != null) {
                        mProgramEndTime = current.getCurrentProgram().getEndDate();
                        LocalDateTime nowTime = LocalDateTime.now();

                        if (nowTime.isAfter(mProgramEndTime)) {
                            UUID channelID = current.getCurrentProgram().getChannelId();
                            BaseItemDto myChannel = TvManager.getChannelByID(channelID);
                            if (myChannel == null) return false;

                            final Lazy<PlaybackHelper> playbackHelper = inject(PlaybackHelper.class);
                            playbackHelper.getValue().getItemsToPlay(requireContext(), myChannel, false, false, new Response<List<BaseItemDto>>() {
                                @Override
                                public void onResponse(List<BaseItemDto> response) {
                                    playbackControllerContainer.getValue().getPlaybackController().setItems(response);
                                    playbackControllerContainer.getValue().getPlaybackController().updateTvProgramInfo();
                                    updateDisplay();
                                }
                            });
                        }
                    }

                    return false;
                }
            }

            return false;
        }
    };

    private boolean isSeekBarFocused() {
        return leanbackOverlayFragment != null
                && leanbackOverlayFragment.getPlayerGlue() != null
                && leanbackOverlayFragment.getPlayerGlue().isSeekBarFocused();
    }

    private void enterSeekConfirmationMode() {
        mPendingSeekConfirmation = true;
        if (leanbackOverlayFragment != null && leanbackOverlayFragment.getPlayerGlue() != null) {
            leanbackOverlayFragment.getPlayerGlue().enterSeekConfirmationMode();
        }
    }

    private void exitSeekConfirmationMode() {
        mPendingSeekConfirmation = false;
        if (leanbackOverlayFragment != null && leanbackOverlayFragment.getPlayerGlue() != null) {
            leanbackOverlayFragment.getPlayerGlue().exitSeekConfirmationMode();
        }
    }

    private void applyPendingSeek() {
        if (mPendingSeekConfirmation) {
            if (leanbackOverlayFragment != null && leanbackOverlayFragment.getPlayerGlue() != null) {
                leanbackOverlayFragment.getPlayerGlue().applyPendingSeek();
            }
            mPendingSeekConfirmation = false;
        }
    }

    public LocalDateTime getCurrentLocalStartDate() {
        return mCurrentGuideStart;
    }

    public LocalDateTime getCurrentLocalEndDate() {
        return mCurrentGuideEnd;
    }

    public void switchChannel(UUID id) {
        switchChannel(id, true);
    }

    public void switchChannel(UUID id, boolean hideGuide) {
        if (playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem().getId().equals(id)) {
            // same channel, just dismiss overlay
            if (hideGuide)
                hideGuide();
        } else {
            playbackControllerContainer.getValue().getPlaybackController().stop();
            if (hideGuide)
                hideGuide();

            CustomPlaybackOverlayFragmentHelperKt.playChannel(this, id);
        }
    }

    private void startFadeTimer() {
        mHandler.removeCallbacks(mHideTask);
        if (isPlaybackPaused()) {
            mFadeEnabled = false;
            if (binding != null && !mGuideVisible && !mPopupPanelVisible) show();
            return;
        }

        mFadeEnabled = true;
        mHandler.postDelayed(mHideTask, 6000);
        WindowCompat.setDecorFitsSystemWindows(requireActivity().getWindow(), false);
        WindowCompat.getInsetsController(requireActivity().getWindow(), requireActivity().getWindow().getDecorView()).hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onResume() {
        super.onResume();

        // Close player when resuming without a valid playback controller
        if (playbackControllerContainer.getValue().getPlaybackController() == null || !playbackControllerContainer.getValue().getPlaybackController().hasFragment()) {
            closePlayer();

            return;
        }

        // Hide system bars
        WindowCompat.setDecorFitsSystemWindows(requireActivity().getWindow(), false);
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
        insetsController.hide(WindowInsetsCompat.Type.systemBars());
        insetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );

        if (mAudioManager.requestAudioFocus(mAudioFocusChanged, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.e("Unable to get audio focus");
            Utils.showToast(requireContext(), R.string.msg_cannot_play_time);
            return;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mGuideLoadRequestId++;
        mProgramLoadRequestId++;
        if (mItemsToPlay == null || mItemsToPlay.isEmpty()) return;

        setPlayPauseActionState(0);

        // give back audio focus
        mAudioManager.abandonAudioFocus(mAudioFocusChanged);
    }

    @Override
    public void onStop() {
        super.onStop();
        Timber.d("Stopping playback overlay");

        if (leanbackOverlayFragment != null)
            leanbackOverlayFragment.setOnKeyInterceptListener(null);

        // end playback from here if this fragment belongs to the current session.
        // if it doesn't, playback has already been stopped elsewhere, and the references to this have been replaced
        if (playbackControllerContainer.getValue().getPlaybackController() != null && playbackControllerContainer.getValue().getPlaybackController().getFragment() == this) {
            Timber.d("This fragment belongs to the current session, ending it");
            playbackControllerContainer.getValue().getPlaybackController().endPlayback();
        }

        closePlayer();
    }

    public void show() {
        // Already showing!
        if (mIsVisible) return;

        binding.topPanel.startAnimation(slideDown);
        mIsVisible = true;
        updateSkipOverlayAvailability();
    }

    public void hide() {
        // Can't hide what's already hidden
        if (!mIsVisible) return;

        mIsVisible = false;
        binding.topPanel.startAnimation(fadeOut);
        updateSkipOverlayAvailability();

        if (leanbackOverlayFragment != null && leanbackOverlayFragment.getPlayerGlue() != null) {
            leanbackOverlayFragment.getPlayerGlue().hideThumbnailPreview();
        }
    }

    private void showChapterPanel() {
        setFadingEnabled(false);
        binding.popupArea.clearAnimation();
        binding.popupArea.startAnimation(showPopup);
        updateSkipOverlayAvailability();
    }

    private void hidePopupPanel() {
        startFadeTimer();
        if (mProgramInfoUpdateTask != null) {
            mHandler.removeCallbacks(mProgramInfoUpdateTask);
        }
        // Don't change visibility before the animation — let the whole panel fade out together.
        // Header/description are reset in hidePopup's onAnimationEnd (which sets popupArea GONE).
        binding.popupArea.startAnimation(hidePopup);
        mPopupPanelVisible = false;
        mQuickChannelChangerVisible = false;
        updateSkipOverlayAvailability();
    }

    public void showGuide() {
        setStreamStatusOverlayVisible(false);
        hide();
        leanbackOverlayFragment.setShouldShowOverlay(false);
        leanbackOverlayFragment.hideOverlay();
        playbackControllerContainer.getValue().getPlaybackController().mVideoManager.contractVideo(Utils.convertDpToPixel(requireContext(), 300));
        tvGuideBinding.getRoot().setVisibility(View.VISIBLE);
        mGuideVisible = true;
        LocalDateTime now = LocalDateTime.now();
        boolean needLoad = mCurrentGuideStart == null;
        if (!needLoad) {
            LocalDateTime needLoadTime = mCurrentGuideStart.plusMinutes(30);
            needLoad = now.isAfter(needLoadTime);
            if (mSelectedProgramView != null)
                requestGuideFocusWithoutAutoLoad(mSelectedProgramView);
        }
        if (needLoad) {
            loadGuide();
        }
        updateSkipOverlayAvailability();
    }

    private void hideGuide() {
        tvGuideBinding.getRoot().setVisibility(View.GONE);
        playbackControllerContainer.getValue().getPlaybackController().mVideoManager.setVideoFullSize(true);
        mGuideVisible = false;
        mGuideLoadRequestId++;
        mProgramLoadRequestId++;
        updateSkipOverlayAvailability();
    }

    private void requestGuideFocusWithoutAutoLoad(View view) {
        mIgnoreNextGuideFocusAutoLoad = true;
        if (view.requestFocus()) {
            view.post(() -> mIgnoreNextGuideFocusAutoLoad = false);
        } else {
            mIgnoreNextGuideFocusAutoLoad = false;
        }
    }

    private void loadGuide() {
        tvGuideBinding.spinner.setVisibility(View.VISIBLE);
        fillTimeLine(GUIDE_HOURS);
        final int requestId = ++mGuideLoadRequestId;
        TvManager.loadAllChannels(this, ndx -> {
            if (requestId != mGuideLoadRequestId) return null;

            if (ndx >= PAGE_SIZE) {
                // last channel is not in first page so grab a set where it will be in the middle
                ndx = ndx - (PAGE_SIZE / 2);
            } else {
                ndx = 0; // just start at beginning
            }

            mAllChannels = TvManager.getAllChannels();
            if (!mAllChannels.isEmpty()) {
                displayChannels(ndx, PAGE_SIZE);
            } else {
                tvGuideBinding.spinner.setVisibility(View.GONE);
            }

            return null;
        });
    }

    public void displayChannels(int start, int max) {
        UUID focusChannelId = max > PAGE_SIZE ? getCurrentGuideFocusChannelId() : null;
        displayChannels(start, max, focusChannelId);
    }

    private void displayChannels(int start, int max, @Nullable UUID focusChannelId) {
        int end = start + max;
        if (end > mAllChannels.size()) end = mAllChannels.size();

        mCurrentDisplayChannelStartNdx = start;
        mCurrentDisplayChannelEndNdx = end - 1;
        if (focusChannelId != null) {
            mFirstFocusChannelId = focusChannelId;
            mPreserveGuideFocusOnNextLoad = true;
        }
        Timber.v("Display channels pre-execute");
        tvGuideBinding.spinner.setVisibility(View.VISIBLE);

        tvGuideBinding.channels.removeAllViews();
        tvGuideBinding.programRows.removeAllViews();
        tvGuideBinding.channelsStatus.setText("");
        tvGuideBinding.filterStatus.setText("");
        final CustomPlaybackOverlayFragment self = this;
        final int requestId = ++mProgramLoadRequestId;
        TvManager.getProgramsAsync(this, mCurrentDisplayChannelStartNdx, mCurrentDisplayChannelEndNdx, mCurrentGuideStart, mCurrentGuideEnd, new EmptyResponse(getLifecycle()) {
            @Override
            public void onResponse() {
                if (!isActive() || requestId != mProgramLoadRequestId) return;
                Timber.v("Programs response");
                if (mDisplayProgramsTask != null) mDisplayProgramsTask.cancel(true);
                mDisplayProgramsTask = new DisplayProgramsTask(self);
                mDisplayProgramsTask.execute(mCurrentDisplayChannelStartNdx, mCurrentDisplayChannelEndNdx);
            }
        });
        updateSkipOverlayAvailability();
    }

    DisplayProgramsTask mDisplayProgramsTask;

    class DisplayProgramsTask extends AsyncTask<Integer, Integer, Void> {
        private View firstRow;
        private int displayedChannels = 0;
        private final LiveTvGuide guide;

        DisplayProgramsTask(LiveTvGuide guide) {
            super();
            this.guide = guide;
        }

        @Override
        protected void onPreExecute() {
            Timber.v("Display programs pre-execute");
            tvGuideBinding.channels.removeAllViews();
            tvGuideBinding.programRows.removeAllViews();
            if (!mPreserveGuideFocusOnNextLoad) {
                mFirstFocusChannelId = playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem().getId();
            }

            if (mCurrentDisplayChannelStartNdx > 0) {
                // Show a paging row for channels above
                int pageUpStart = mCurrentDisplayChannelStartNdx - PAGE_SIZE;
                if (pageUpStart < 0) pageUpStart = 0;

                TextView placeHolder = new TextView(requireContext());
                placeHolder.setHeight(Utils.convertDpToPixel(requireContext(), LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP));
                tvGuideBinding.channels.addView(placeHolder);
                displayedChannels = 0;

                String label = TextUtilsKt.getLoadChannelsLabel(requireContext(), mAllChannels.get(pageUpStart).getNumber(), mAllChannels.get(mCurrentDisplayChannelStartNdx - 1).getNumber());
                tvGuideBinding.programRows.addView(new GuidePagingButton(requireContext(), guide, pageUpStart, mCurrentDisplayChannelEndNdx - pageUpStart + 1, label));
            }
        }

        @Override
        protected Void doInBackground(Integer... params) {
            int start = params[0];
            int end = params[1];

            boolean first = true;

            Timber.v("About to iterate programs");
            LinearLayout prevRow = null;
            for (int i = start; i <= end; i++) {
                if (isCancelled()) return null;
                final BaseItemDto channel = TvManager.getChannel(i);
                List<BaseItemDto> programs = TvManager.getProgramsForChannel(channel.getId());
                final LinearLayout row = getProgramRow(programs, channel.getId());
                if (first) {
                    first = false;
                    firstRow = row;
                }

                // put focus on the last tuned channel
                if (channel.getId().equals(mFirstFocusChannelId)) {
                    firstRow = row;
                    mFirstFocusChannelId = null; // only do this first time in not while paging around
                    mPreserveGuideFocusOnNextLoad = false;
                }

                // set focus parameters if we are not on first row
                // this makes focus movements more predictable for the grid view
                if (prevRow != null) {
                    TvManager.setFocusParams(row, prevRow, true);
                    TvManager.setFocusParams(prevRow, row, false);
                }
                prevRow = row;

                requireActivity().runOnUiThread(() -> {
                    GuideChannelHeader header = getChannelHeader(requireContext(), channel);
                    tvGuideBinding.channels.addView(header);
                    header.loadImage();
                    tvGuideBinding.programRows.addView(row);
                });

                displayedChannels++;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            Timber.v("Display programs post execute");
            if (mCurrentDisplayChannelEndNdx < mAllChannels.size() - 1) {
                // Show a paging row for channels below
                int pageDnEnd = mCurrentDisplayChannelEndNdx + PAGE_SIZE;
                if (pageDnEnd >= mAllChannels.size()) pageDnEnd = mAllChannels.size() - 1;

                TextView placeHolder = new TextView(requireContext());
                placeHolder.setHeight(Utils.convertDpToPixel(requireContext(), LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP));
                tvGuideBinding.channels.addView(placeHolder);

                String label = TextUtilsKt.getLoadChannelsLabel(requireContext(), mAllChannels.get(mCurrentDisplayChannelEndNdx + 1).getNumber(), mAllChannels.get(pageDnEnd).getNumber());
                tvGuideBinding.programRows.addView(new GuidePagingButton(requireContext(), guide, mCurrentDisplayChannelStartNdx, pageDnEnd - mCurrentDisplayChannelStartNdx + 1, label));
            }

            tvGuideBinding.channelsStatus.setText(getResources().getString(R.string.lbl_tv_channel_status, displayedChannels, mAllChannels.size()));
            tvGuideBinding.filterStatus.setText(getResources().getString(R.string.lbl_tv_filter_status, GUIDE_HOURS));

            tvGuideBinding.spinner.setVisibility(View.GONE);
            mPreserveGuideFocusOnNextLoad = false;

            if (firstRow != null) requestGuideFocusWithoutAutoLoad(firstRow);
        }
    }

    private int currentCellId = 0;

    private GuideChannelHeader getChannelHeader(Context context, BaseItemDto channel) {
        return new GuideChannelHeader(context, this, channel);
    }

    private LinearLayout getProgramRow(List<BaseItemDto> programs, UUID channelId) {
        int guideRowHeightPx = Utils.convertDpToPixel(requireContext(), LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP);
        int guideRowWidthPerMinPx = Utils.convertDpToPixel(requireContext(), LiveTvGuideFragment.GUIDE_ROW_WIDTH_PER_MIN_DP);

        LinearLayout programRow = new LinearLayout(requireContext());
        if (programs.size() == 0) {

            int minutes = ((Long) ((mCurrentGuideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - mCurrentGuideStart.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000)).intValue();
            int slot = 0;

            do {
                BaseItemDto empty = LiveTvGuideFragmentHelperKt.createNoProgramDataBaseItem(
                        getContext(),
                        channelId,
                        mCurrentGuideStart.plusMinutes(30l * slot),
                        mCurrentGuideEnd.plusMinutes(30l * (slot + 1))
                );
                ProgramGridCell cell = new ProgramGridCell(requireContext(), this, empty, false);
                cell.setId(currentCellId++);
                cell.setLayoutParams(new ViewGroup.LayoutParams(30 * guideRowWidthPerMinPx, guideRowHeightPx));
                programRow.addView(cell);
                if (slot == 0)
                    cell.setFirst();
                if (slot == (minutes / 30) - 1)
                    cell.setLast();
                slot++;
            } while ((30 * slot) < minutes);

            return programRow;
        }

        LocalDateTime prevEnd = getCurrentLocalStartDate();
        for (BaseItemDto item : programs) {
            LocalDateTime start = item.getStartDate() != null ? item.getStartDate() : getCurrentLocalStartDate();
            if (start.isBefore(getCurrentLocalStartDate())) start = getCurrentLocalStartDate();
            if (start.isAfter(getCurrentLocalEndDate())) continue;
            if (start.isBefore(prevEnd)) continue;

            if (start.isAfter(prevEnd)) {
                BaseItemDto empty = LiveTvGuideFragmentHelperKt.createNoProgramDataBaseItem(
                        getContext(),
                        channelId,
                        prevEnd,
                        start
                );
                ProgramGridCell cell = new ProgramGridCell(requireContext(), this, empty, false);
                cell.setId(currentCellId++);
                cell.setLayoutParams(new ViewGroup.LayoutParams(((Long) ((start.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000)).intValue() * guideRowWidthPerMinPx, guideRowHeightPx));
                programRow.addView(cell);
            }
            LocalDateTime end = item.getEndDate() != null ? item.getEndDate() : getCurrentLocalEndDate();
            if (end.isAfter(getCurrentLocalEndDate())) end = getCurrentLocalEndDate();
            prevEnd = end;
            Long duration = (end.toInstant(ZoneOffset.UTC).toEpochMilli() - start.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000;
            if (duration > 0) {
                ProgramGridCell program = new ProgramGridCell(requireContext(), this, item, false);
                program.setId(currentCellId++);
                program.setLayoutParams(new ViewGroup.LayoutParams(duration.intValue() * guideRowWidthPerMinPx, guideRowHeightPx));

                if (start == getCurrentLocalStartDate())
                    program.setFirst();
                if (end == getCurrentLocalEndDate())
                    program.setLast();

                programRow.addView(program);
            }
        }

        return programRow;
    }

    private void fillTimeLine(int hours) {
        mCurrentGuideStart = LocalDateTime.now();
        mCurrentGuideStart = mCurrentGuideStart
                .withMinute(mCurrentGuideStart.getMinute() >= 30 ? 30 : 0)
                .withSecond(0)
                .withNano(0);

        tvGuideBinding.displayDate.setText(TimeUtils.getFriendlyDate(requireContext(), mCurrentGuideStart));
        mCurrentGuideEnd = mCurrentGuideStart
                .plusHours(hours);
        int oneHour = 60 * Utils.convertDpToPixel(requireContext(), 7);
        int halfHour = 30 * Utils.convertDpToPixel(requireContext(), 7);
        int interval = mCurrentGuideStart.getMinute() >= 30 ? 30 : 60;
        tvGuideBinding.timeline.removeAllViews();

        LocalDateTime current = mCurrentGuideStart;
        while (current.isBefore(mCurrentGuideEnd)) {
            TextView time = new TextView(requireContext());
            time.setText(DateTimeExtensionsKt.getTimeFormatter(getContext()).format(current));
            time.setWidth(interval == 30 ? halfHour : oneHour);
            tvGuideBinding.timeline.addView(time);
            current = current.plusMinutes(interval);
            //after first one, we always go on hours
            interval = 60;
        }
    }

    private Runnable detailUpdateTask = new Runnable() {
        @Override
        public void run() {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
            CustomPlaybackOverlayFragmentHelperKt.refreshSelectedProgram(CustomPlaybackOverlayFragment.this);
        }
    };

    void detailUpdateInternal() {
        tvGuideBinding.guideTitle.setText(mSelectedProgram.getName());
        tvGuideBinding.summary.setText(mSelectedProgram.getOverview());
        //info row
        InfoLayoutHelper.addInfoRow(requireContext(), mSelectedProgram, tvGuideBinding.guideInfoRow, false);
        if (mSelectedProgram.getId() != null) {
            tvGuideBinding.displayDate.setText(TimeUtils.getFriendlyDate(requireContext(), mSelectedProgram.getStartDate()));
        }

        if (mDetailPopup != null && mDetailPopup.isShowing() && mSelectedProgramView != null) {
            mDetailPopup.setContent(mSelectedProgram, ((ProgramGridCell) mSelectedProgramView));
        }
    }

    private void maybeAutoLoadAdjacentChannels(RelativeLayout programView) {
        if (!mGuideVisible || tvGuideBinding == null || tvGuideBinding.spinner.getVisibility() == View.VISIBLE || mAllChannels == null || mAllChannels.isEmpty()) {
            return;
        }

        Integer channelIndex = getDisplayedChannelIndex(programView);
        UUID focusChannelId = getGuideFocusChannelId(programView);
        if (channelIndex == null || focusChannelId == null) return;

        if (channelIndex >= mCurrentDisplayChannelEndNdx - AUTO_LOAD_CHANNEL_THRESHOLD && mCurrentDisplayChannelEndNdx < mAllChannels.size() - 1) {
            int newEnd = Math.min(mAllChannels.size() - 1, mCurrentDisplayChannelEndNdx + PAGE_SIZE);
            displayChannels(mCurrentDisplayChannelStartNdx, newEnd - mCurrentDisplayChannelStartNdx + 1, focusChannelId);
        } else if (channelIndex <= mCurrentDisplayChannelStartNdx + AUTO_LOAD_CHANNEL_THRESHOLD && mCurrentDisplayChannelStartNdx > 0) {
            int newStart = Math.max(0, mCurrentDisplayChannelStartNdx - PAGE_SIZE);
            displayChannels(newStart, mCurrentDisplayChannelEndNdx - newStart + 1, focusChannelId);
        }
    }

    @Nullable
    private Integer getDisplayedChannelIndex(RelativeLayout programView) {
        int rowIndex = -1;
        if (programView instanceof GuideChannelHeader) {
            for (int i = 0; i < tvGuideBinding.channels.getChildCount(); i++) {
                if (programView == tvGuideBinding.channels.getChildAt(i)) {
                    rowIndex = i;
                    break;
                }
            }
        } else if (programView instanceof ProgramGridCell) {
            View parent = (View) programView.getParent();
            for (int i = 0; i < tvGuideBinding.programRows.getChildCount(); i++) {
                if (parent == tvGuideBinding.programRows.getChildAt(i)) {
                    rowIndex = i;
                    break;
                }
            }
        }

        if (rowIndex < 0) return null;

        int topPagingRows = mCurrentDisplayChannelStartNdx > 0 ? 1 : 0;
        int displayedOffset = rowIndex - topPagingRows;
        int displayedChannelCount = mCurrentDisplayChannelEndNdx - mCurrentDisplayChannelStartNdx + 1;
        if (displayedOffset < 0 || displayedOffset >= displayedChannelCount) return null;

        return mCurrentDisplayChannelStartNdx + displayedOffset;
    }

    @Nullable
    private UUID getCurrentGuideFocusChannelId() {
        if (mSelectedProgramView != null) {
            return getGuideFocusChannelId(mSelectedProgramView);
        } else if (mSelectedProgram != null) {
            return mSelectedProgram.getChannelId();
        }

        return null;
    }

    @Nullable
    private UUID getGuideFocusChannelId(RelativeLayout programView) {
        if (programView instanceof GuideChannelHeader) {
            return ((GuideChannelHeader) programView).getChannel().getId();
        } else if (programView instanceof ProgramGridCell) {
            return ((ProgramGridCell) programView).getProgram().getChannelId();
        } else if (mSelectedProgram != null) {
            return mSelectedProgram.getChannelId();
        }

        return null;
    }

    public void setSelectedProgram(RelativeLayout programView) {
        mSelectedProgramView = programView;
        if (mIgnoreNextGuideFocusAutoLoad) {
            mIgnoreNextGuideFocusAutoLoad = false;
        } else {
            programView.post(() -> maybeAutoLoadAdjacentChannels(programView));
        }
        if (mSelectedProgramView instanceof ProgramGridCell) {
            mSelectedProgram = ((ProgramGridCell) mSelectedProgramView).getProgram();
            mHandler.removeCallbacks(detailUpdateTask);
            mHandler.postDelayed(detailUpdateTask, 500);
        } else if (mSelectedProgramView instanceof GuideChannelHeader) {
            for (int i = 0; i < tvGuideBinding.channels.getChildCount(); i++) {
                if (mSelectedProgramView == tvGuideBinding.channels.getChildAt(i)) {
                    LinearLayout programRow = (LinearLayout) tvGuideBinding.programRows.getChildAt(i);
                    if (programRow == null)
                        return;
                    for (int ii = 0; ii < programRow.getChildCount(); ii++) {
                        ProgramGridCell prog = (ProgramGridCell) programRow.getChildAt(ii);
                        if (prog.getProgram() != null && prog.getProgram().getStartDate().isBefore(LocalDateTime.now()) && prog.getProgram().getEndDate().isAfter(LocalDateTime.now())) {
                            mSelectedProgram = prog.getProgram();
                            if (mSelectedProgram != null) {
                                mHandler.removeCallbacks(detailUpdateTask);
                                mHandler.postDelayed(detailUpdateTask, 500);
                            }
                            return;
                        }
                    }
                }
            }
        }
    }

    public void dismissProgramOptions() {
        if (mDetailPopup != null) mDetailPopup.dismiss();
    }

    private LiveProgramDetailPopup mDetailPopup;

    public void showProgramOptions() {
        if (mSelectedProgram == null) return;
        if (mDetailPopup == null)
            mDetailPopup = new LiveProgramDetailPopup(requireActivity(), this, this, Utils.convertDpToPixel(requireContext(), 600), new EmptyResponse(getLifecycle()) {
                @Override
                public void onResponse() {
                    if (!isActive()) return;
                    switchChannel(mSelectedProgram.getChannelId());
                }
            });
        mDetailPopup.setContent(mSelectedProgram, (ProgramGridCell) mSelectedProgramView);
        mDetailPopup.show(tvGuideBinding.guideTitle, 0, tvGuideBinding.guideTitle.getTop() - 10);

    }

    private Animation.AnimationListener hideAnimationListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            binding.topPanel.setVisibility(View.GONE);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    };

    private Animation.AnimationListener showAnimationListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            binding.topPanel.setVisibility(View.VISIBLE);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    };

    public void showQuickChannelChanger() {
        mQuickChannelChangerVisible = true;
        // Show header and reserve description space for channels
        binding.popupHeader.setText("");
        binding.popupHeader.setVisibility(View.VISIBLE);
        binding.popupDescription.setText("");
        binding.popupDescription.setVisibility(View.VISIBLE);

        // Pre-position if the adapter is already loaded. If it isn't ready yet
        // (async load in prepareChannelAdapter), that callback will set position
        // when it completes — the presenter queues it until the row is bound.
        positionQuickChannelIfReady();

        mPopupPanelVisible = true;
        showChapterPanel();
    }

    private void positionQuickChannelIfReady() {
        if (binding == null || mCircularChannelAdapter == null) return;

        int idx = TvManager.getAllChannelsIndex(TvManager.getLastLiveTvChannel());
        // If the "last channel" index is temporarily stale (for example after tuning
        // to a brand-new channel from guide), keep circular behavior by centering on
        // the first item instead of leaving selection at position 0 (hard left edge).
        if (idx < 0) idx = 0;

        int centeredPosition = mCircularChannelAdapter.centerPosition(idx);
        if (centeredPosition < 0) return;

        mPopupRowPresenter.setPosition(centeredPosition);

        // Populate description for initially focused channel in case selection callback
        // does not fire when the popup first appears.
        Object focusedItem = mCircularChannelAdapter.get(centeredPosition);
        if (!(focusedItem instanceof BaseItemDto)) return;

        BaseItemDto program = ((BaseItemDto) focusedItem).getCurrentProgram();
        String overview = (program != null) ? program.getOverview() : null;
        if (overview != null && !overview.isEmpty()) {
            binding.popupDescription.setText(overview);
        }
        binding.popupHeader.setText(getProgramHeaderText(program));
    }

    private String getProgramHeaderText(BaseItemDto program) {
        if (program == null) return "";

        Integer season = program.getParentIndexNumber();
        Integer episode = program.getIndexNumber();
        Integer episodeEnd = program.getIndexNumberEnd();

        String seFragment = null;
        if (episode != null) {
            String ePart = (episodeEnd != null)
                    ? getString(R.string.lbl_episode_range, episode, episodeEnd)
                    : getString(R.string.lbl_episode_number, episode);
            seFragment = (season != null)
                    ? getString(R.string.lbl_season_number, season) + ":" + ePart
                    : ePart;
        }

        String episodeTitle = program.getEpisodeTitle();
        String title = (episodeTitle != null && !episodeTitle.isEmpty()) ? episodeTitle : program.getName();
        if (title == null) title = "";

        if (seFragment == null) return title;
        if (title.isEmpty()) return seFragment;
        return title + " (" + seFragment + ")";
    }

    public void showChapterSelector() {
        mQuickChannelChangerVisible = false;
        // Show header for chapters (no description area needed)
        binding.popupHeader.setText(R.string.chapters);
        binding.popupHeader.setVisibility(View.VISIBLE);
        binding.popupDescription.setVisibility(View.GONE);
        // Position before the panel animates in. If the row isn't bound yet,
        // the presenter queues the position until onBindRowViewHolder fires.
        PlaybackController controller =
            playbackControllerContainer.getValue().getPlaybackController();
        if (controller == null) return;
        BaseItemDto currentItem = controller.getCurrentlyPlayingItem();
        if (currentItem == null) return;
        int ndx = getCurrentChapterIndex(currentItem,
            controller.getCurrentPosition() * TICKS_PER_MS);
        if (ndx >= 0 && mCircularChapterAdapter != null) {
            mPopupRowPresenter.setPosition(mCircularChapterAdapter.centerPosition(ndx));
        }
        mPopupPanelVisible = true;
        showChapterPanel();
    }

    private int getCurrentChapterIndex(BaseItemDto item, long pos) {
        int ndx = 0;
        if (item.getChapters() != null) {
            for (ChapterInfo chapter : item.getChapters()) {
                if (chapter.getStartPositionTicks() > pos) return ndx - 1;
                ndx++;
            }
        }
        return ndx - 1;
    }

    public void toggleRecording(BaseItemDto item) {
        final BaseItemDto program = item.getCurrentProgram();

        if (program != null) {
            if (program.getTimerId() != null) {
                // cancel
                if (program.getSeriesTimerId() != null) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.lbl_cancel_recording)
                            .setMessage(R.string.msg_cancel_entire_series)
                            .setPositiveButton(R.string.lbl_cancel_series, (dialog, which) -> cancelRecording(program, true))
                            .setNegativeButton(R.string.just_one, (dialog, which) -> cancelRecording(program, false))
                            .show();
                } else {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.lbl_cancel_recording)
                            .setPositiveButton(R.string.lbl_yes, (dialog, which) -> cancelRecording(program, false))
                            .setNegativeButton(R.string.lbl_no, null)
                            .show();
                }
            } else {
                if (Utils.isTrue(program.isSeries())) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.lbl_record_series)
                            .setMessage(R.string.msg_record_entire_series)
                            .setPositiveButton(R.string.lbl_record_series, (dialog, which) -> CustomPlaybackOverlayFragmentHelperKt.recordProgram(this, program, true))
                            .setNegativeButton(R.string.lbl_just_this_once, (dialog, which) -> CustomPlaybackOverlayFragmentHelperKt.recordProgram(this, program, false))
                            .show();
                } else {
                    CustomPlaybackOverlayFragmentHelperKt.recordProgram(this, program, false);
                }
            }
        }
    }

    private void cancelRecording(BaseItemDto program, boolean series) {
        if (program != null) {
            if (series) {
                CustomPlaybackOverlayFragmentHelperKt.cancelSeriesTimer(this, program.getSeriesTimerId());
            } else {
                CustomPlaybackOverlayFragmentHelperKt.cancelTimer(this, program.getTimerId());
            }
        }
    }

    public void setCurrentTime(long time) {
        binding.skipOverlay.setCurrentPositionMs(time);
        updateSkipOverlayHitTarget();
        updatePlaybackDebugInfo();
        if (leanbackOverlayFragment != null)
            leanbackOverlayFragment.updateCurrentPosition();
    }

    public void setSecondaryTime(long time) {
    }

    public void setFadingEnabled(boolean value) {
        mFadeEnabled = value;
        if (mFadeEnabled) {
            startFadeTimer();
        } else {
            mHandler.removeCallbacks(mHideTask);
            if (binding != null && isPlaybackPaused() && !mGuideVisible && !mPopupPanelVisible) show();
        }
    }

    public void setPlayPauseActionState(final int state) {
        leanbackOverlayFragment.updatePlayState();
    }

    public void updateDisplay() {
        BaseItemDto current = playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem();
        if (current != null && getContext() != null) {
            leanbackOverlayFragment.mediaInfoChanged();
            leanbackOverlayFragment.onFullyInitialized();
            leanbackOverlayFragment.recordingStateChanged();
            // set progress to match duration
            // set other information
            tvGuideBinding.guideCurrentTitle.setText(current.getName());

            // Update the title and subtitle
            if (current.getType() == BaseItemKind.EPISODE) {
                binding.itemTitle.setText(current.getSeriesName());
                binding.itemSubtitle.setText(BaseItemExtensionsKt.getDisplayName(current, requireContext()));
            } else if (current.getType() == BaseItemKind.TV_CHANNEL) {
                binding.itemTitle.setText(current.getName());
                if (current.getCurrentProgram() != null) {
                    if (current.getCurrentProgram().getEpisodeTitle() != null) {
                        binding.itemSubtitle.setText(current.getCurrentProgram().getName() + " - " + current.getCurrentProgram().getEpisodeTitle());
                    } else {
                        binding.itemSubtitle.setText(current.getCurrentProgram().getName());
                    }
                    mProgramEndTime = current.getCurrentProgram().getEndDate();
                } else {
                    binding.itemSubtitle.setText("");
                }
            } else {
                binding.itemTitle.setText(current.getName());
            }
            // Update the logo
            String imageUrl = imageHelper.getValue().getLogoImageUrl(current, 440);
            if (imageUrl != null) {
                binding.itemLogo.setVisibility(View.VISIBLE);
                binding.itemTitle.setVisibility(View.GONE);
                binding.itemLogo.setContentDescription(current.getName());
                binding.itemLogo.load(imageUrl, null, null, 1.0, 0);
            } else {
                binding.itemLogo.setVisibility(View.GONE);
                binding.itemTitle.setVisibility(View.VISIBLE);
            }

            updatePlaybackDebugInfo();

            if (playbackControllerContainer.getValue().getPlaybackController().isLiveTv()) {
                prepareChannelAdapter();
            } else {
                prepareChapterAdapter();
            }
        }
    }

    private void updatePlaybackDebugInfo() {
        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        if (playbackController == null || playbackController.getCurrentStreamInfo() == null) {
            binding.playbackDebugInfo.setVisibility(View.GONE);
            updateStreamStatusOverlay(playbackController);
            return;
        }

        String debugInfo = buildPlaybackDebugInfo(playbackController);
        if (debugInfo == null || debugInfo.isEmpty()) {
            binding.playbackDebugInfo.setVisibility(View.GONE);
            updateStreamStatusOverlay(playbackController);
            return;
        }

        binding.playbackDebugInfo.setText(debugInfo);
        binding.playbackDebugInfo.setVisibility(View.VISIBLE);
        updateStreamStatusOverlay(playbackController);
    }

    public void toggleStreamStatusOverlay() {
        setStreamStatusOverlayVisible(!mStreamStatusOverlayVisible);
    }

    private void setStreamStatusOverlayVisible(boolean visible) {
        mStreamStatusOverlayVisible = visible;
        if (binding == null) return;

        binding.streamStatusOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            updateStreamStatusOverlay(playbackControllerContainer.getValue().getPlaybackController());
            startFadeTimer();
        }
    }

    private void updateStreamStatusOverlay(PlaybackController playbackController) {
        if (binding == null || !mStreamStatusOverlayVisible) return;

        if (playbackController == null || playbackController.getCurrentStreamInfo() == null) {
            binding.streamStatusText.setText(R.string.playback_info);
            return;
        }

        refreshTranscodingStatus(playbackController);
        binding.streamStatusText.setText(StreamStatusBuilder.build(playbackController, mTranscodingInfo));
    }

    private void refreshTranscodingStatus(PlaybackController playbackController) {
        if (playbackController == null || playbackController.getCurrentStreamInfo() == null) return;

        if (playbackController.getCurrentStreamInfo().getPlayMethod() != PlayMethod.TRANSCODE) {
            mTranscodingStatusKey = null;
            mTranscodingInfo = null;
            return;
        }

        String key = getTranscodingStatusKey(playbackController);
        if (key == null) return;
        if (!key.equals(mTranscodingStatusKey)) {
            mTranscodingStatusKey = key;
            mTranscodingInfo = null;
            mLastTranscodingStatusFetchMs = 0;
        }

        long now = System.currentTimeMillis();
        if (mTranscodingStatusFetchInFlight || now - mLastTranscodingStatusFetchMs < TRANSCODING_STATUS_REFRESH_MS) {
            return;
        }

        mLastTranscodingStatusFetchMs = now;
        mTranscodingStatusFetchInFlight = true;

        String playSessionId = playbackController.getCurrentStreamInfo().getPlaySessionId();
        BaseItemDto item = playbackController.getCurrentlyPlayingItem();
        UUID itemId = item == null ? null : item.getId();
        String mediaSourceId = playbackController.getCurrentStreamInfo().getMediaSourceId();

        mTranscodingStatusExecutor.execute(() -> {
            TranscodingInfo transcodingInfo = null;
            try {
                transcodingInfo = transcodingStatusRepository.getValue().getTranscodingInfoBlocking(
                        playSessionId,
                        itemId,
                        mediaSourceId
                );
            } catch (Exception err) {
                Timber.w(err, "Unable to fetch transcoding status");
            }

            TranscodingInfo finalTranscodingInfo = transcodingInfo;
            mHandler.post(() -> {
                mTranscodingStatusFetchInFlight = false;
                if (!isAdded() || binding == null || !key.equals(mTranscodingStatusKey)) return;

                mTranscodingInfo = finalTranscodingInfo;
                updateStreamStatusOverlay(playbackControllerContainer.getValue().getPlaybackController());
            });
        });
    }

    private String getTranscodingStatusKey(PlaybackController playbackController) {
        if (playbackController == null || playbackController.getCurrentStreamInfo() == null) return null;

        BaseItemDto item = playbackController.getCurrentlyPlayingItem();
        String playSessionId = playbackController.getCurrentStreamInfo().getPlaySessionId();
        String mediaSourceId = playbackController.getCurrentStreamInfo().getMediaSourceId();
        UUID itemId = item == null ? null : item.getId();

        return String.valueOf(playSessionId) + "|" + String.valueOf(itemId) + "|" + String.valueOf(mediaSourceId);
    }

    private String buildPlaybackDebugInfo(PlaybackController playbackController) {
        MediaSourceInfo mediaSource = playbackController.getCurrentMediaSource();
        if (mediaSource == null && playbackController.getCurrentStreamInfo() != null) {
            mediaSource = playbackController.getCurrentStreamInfo().getMediaSource();
        }

        MediaStream videoStream = getStream(mediaSource, MediaStreamType.VIDEO, -1);
        MediaStream audioStream = getStream(mediaSource, MediaStreamType.AUDIO, playbackController.getAudioStreamIndex());
        MediaStream subtitleStream = getStream(mediaSource, MediaStreamType.SUBTITLE, playbackController.getSubtitleStreamIndex());

        StringBuilder info = new StringBuilder();
        appendPart(info, playMethodLabel(playbackController.getCurrentStreamInfo().getPlayMethod()));
        appendPart(info, videoSummary(videoStream));
        appendPart(info, audioSummary(audioStream));
        appendPart(info, subtitleSummary(subtitleStream, playbackController.isBurningSubtitlesForStatus()));

        return info.toString();
    }

    private MediaStream getStream(MediaSourceInfo mediaSource, MediaStreamType type, int index) {
        if (mediaSource == null || mediaSource.getMediaStreams() == null) return null;

        for (MediaStream stream : mediaSource.getMediaStreams()) {
            if (stream.getType() == type && stream.getIndex() == index) return stream;
        }

        for (MediaStream stream : mediaSource.getMediaStreams()) {
            if (stream.getType() == type) return stream;
        }

        return null;
    }

    private void appendPart(StringBuilder builder, String value) {
        if (value == null || value.isEmpty()) return;
        if (builder.length() > 0) builder.append(" • ");
        builder.append(value);
    }

    private String playMethodLabel(PlayMethod playMethod) {
        if (playMethod == PlayMethod.DIRECT_PLAY) return "Direct play";
        if (playMethod == PlayMethod.DIRECT_STREAM) return "Direct stream";
        if (playMethod == PlayMethod.TRANSCODE) return "Transcoding";
        return playMethod == null ? "Unknown" : playMethod.toString();
    }

    private String videoSummary(MediaStream stream) {
        if (stream == null) return null;

        StringBuilder summary = new StringBuilder();
		if (stream.getWidth() != null && stream.getHeight() != null) {
			summary.append(stream.getWidth()).append("x").append(stream.getHeight());
		}
		appendInline(summary, stream.getCodec() == null ? null : stream.getCodec().toUpperCase());
		appendInline(summary, stream.getVideoRange() == null ? null : stream.getVideoRange().toString());
		return summary.toString();
	}

    private String audioSummary(MediaStream stream) {
        if (stream == null) return "Audio: unknown";

        StringBuilder summary = new StringBuilder("Audio:");
        appendInline(summary, stream.getCodec() == null ? null : stream.getCodec().toUpperCase());
        if (stream.getChannels() != null) appendInline(summary, stream.getChannels() + "ch");
        appendInline(summary, LanguageUtils.toIso2LanguageDisplayOrSelf(stream.getLanguage()));
        return summary.toString();
    }

    private String subtitleSummary(MediaStream stream, boolean burningSubtitles) {
        if (burningSubtitles) return "Sub: burned";
        if (stream == null) return "Sub: off";

        StringBuilder summary = new StringBuilder("Sub:");
        appendInline(summary, stream.getCodec() == null ? null : stream.getCodec().toUpperCase());
        appendInline(summary, LanguageUtils.toIso2LanguageDisplayOrSelf(stream.getLanguage()));
        if (stream.isForced()) appendInline(summary, "forced");
        return summary.toString();
    }

    private void appendInline(StringBuilder builder, String value) {
        if (value == null || value.isEmpty()) return;
        if (builder.length() > 0) builder.append(' ');
        builder.append(value);
    }

    public void clearSkipOverlay() {
        binding.skipOverlay.setTargetPositionMs(null);
        updateSkipOverlayHitTarget();
    }

    private void prepareChapterAdapter() {
        BaseItemDto item = playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem();
        List<ChapterInfo> chapters = item.getChapters();

        if (chapters != null && !chapters.isEmpty()) {
            // create chapter row with circular scrolling
            ItemRowAdapter chapterAdapter = new ItemRowAdapter(requireContext(), BaseItemExtensionsKt.buildChapterItems(item), new CardPresenter(true, 110), new MutableObjectAdapter<Row>());
            chapterAdapter.Retrieve();
            mCircularChapterAdapter = new CircularObjectAdapter(chapterAdapter);
            if (mChapterRow != null) mPopupRowAdapter.remove(mChapterRow);
            mPopupRowPresenter.invalidate();
            mChapterRow = new ListRow(mCircularChapterAdapter);
            mPopupRowAdapter.add(mChapterRow);
        }

    }

    private void prepareChannelAdapter() {
        UUID focusedChannelId = null;
        if (mQuickChannelChangerVisible && mCircularChannelAdapter != null) {
            int focusedPosition = mPopupRowPresenter.getPosition();
            if (focusedPosition > 0 && mCircularChannelAdapter.getRealSize() > 0) {
                Object focusedItem = mCircularChannelAdapter.get(focusedPosition);
                if (focusedItem instanceof BaseItemDto) {
                    focusedChannelId = ((BaseItemDto) focusedItem).getId();
                }
            }
        }

        // create quick channel change row with circular scrolling
        UUID finalFocusedChannelId = focusedChannelId;
        TvManager.loadAllChannels(this, response -> {
            if (binding == null) return null;
            List<BaseItemDto> channels = TvManager.getAllChannels();
            if (channels == null) return null;
            ArrayObjectAdapter innerAdapter = new ArrayObjectAdapter(new ChannelCardPresenter());
            innerAdapter.addAll(0, channels);
            mCircularChannelAdapter = new CircularObjectAdapter(innerAdapter);
            if (mChapterRow != null) mPopupRowAdapter.remove(mChapterRow);
            // The invalidate here deals with a very annoying problem.
            // It's pseudo-better "work around" to a prior attempt to deal with this.
            // As best as I can tell, RecyclerView defers unbind until its next layout pass,
            // so the presenter still holds a stale viewHolder.
            // Explictly invalidate it so the position set below goes to pendingPosition
            // and is applied when the new row is bound in onBindRowViewHolder.
            // This also applies to chapters.
            mPopupRowPresenter.invalidate();
            mChapterRow = new ListRow(mCircularChannelAdapter);
            mPopupRowAdapter.add(mChapterRow);

            if (mQuickChannelChangerVisible) {
                int focusIndex = -1;
                if (finalFocusedChannelId != null) {
                    focusIndex = TvManager.getAllChannelsIndex(finalFocusedChannelId);
                }
                if (focusIndex < 0) {
                    focusIndex = TvManager.getAllChannelsIndex(TvManager.getLastLiveTvChannel());
                }
                // Fall back to first channel if the target wasn't found.
                if (focusIndex < 0) focusIndex = 0;
                int pos = mCircularChannelAdapter.centerPosition(focusIndex);
                if (pos >= 0) {
                    mPopupRowPresenter.setPosition(pos);
                    Object focusedItem = mCircularChannelAdapter.get(pos);
                    if (focusedItem instanceof BaseItemDto) {
                        BaseItemDto program = ((BaseItemDto) focusedItem).getCurrentProgram();
                        String overview = (program != null) ? program.getOverview() : null;
                        binding.popupDescription.setText(overview != null ? overview : "");
                        binding.popupHeader.setText(getProgramHeaderText(program));
                    }
                }
            }
            return null;
        });
    }

    public void closePlayer() {
        if (navigating) return;
        navigating = true;

        if (navigationRepository.getValue().getCanGoBack()) {
            navigationRepository.getValue().goBack();
        } else {
            navigationRepository.getValue().reset(Destinations.INSTANCE.getHome());
        }
    }

    public void showNextUp(@NonNull UUID id) {
        if (navigating) return;
        navigating = true;

        navigationRepository.getValue().navigate(Destinations.INSTANCE.nextUp(id), true);
    }

    public void showStillWatching(@NonNull UUID id) {
        if (navigating) return;
        navigating = true;

        navigationRepository.getValue().navigate(Destinations.INSTANCE.stillWatching(id), true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mTranscodingStatusExecutor.shutdownNow();

        // Show system bars
        WindowCompat.setDecorFitsSystemWindows(requireActivity().getWindow(), true);
        WindowCompat.getInsetsController(requireActivity().getWindow(), requireActivity().getWindow().getDecorView()).show(WindowInsetsCompat.Type.systemBars());

        // Reset display mode
        WindowManager.LayoutParams params = getActivity().getWindow().getAttributes();
        params.preferredDisplayModeId = 0;
        getActivity().getWindow().setAttributes(params);
    }
}
