package org.jellyfin.androidtv.ui.browsing;

import static org.koin.java.KoinJavaComponent.inject;

import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.leanback.widget.BaseGridView;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.lifecycle.Lifecycle;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.databinding.HorizontalGridBrowseBinding;
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem;
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher;
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter;
import org.jellyfin.androidtv.ui.presentation.ChannelCardPresenter;
import org.jellyfin.androidtv.ui.presentation.HorizontalGridPresenter;
import org.jellyfin.androidtv.util.KeyProcessor;
import org.jellyfin.androidtv.util.Utils;

import java.text.MessageFormat;

import kotlin.Lazy;

public class BrowseLiveTvChannelsFragment extends Fragment implements View.OnKeyListener {
    private static final int CHANNEL_CHUNK_SIZE = 100;
    private static final int CHANNEL_ROWS = 4;
    private static final int MIN_GRID_SPACING_DP = 4;
    private static final int MAX_GRID_SPACING_DP = 8;
    private static final int MIN_GRID_PADDING_DP = 6;
    private static final int MAX_GRID_PADDING_DP = 18;

    private final Handler handler = new Handler();
    private final Lazy<ItemLauncher> itemLauncher = inject(ItemLauncher.class);
    private final Lazy<KeyProcessor> keyProcessor = inject(KeyProcessor.class);

    private HorizontalGridBrowseBinding binding;
    private ItemRowAdapter adapter;
    private HorizontalGridPresenter gridPresenter;
    private BaseGridView gridView;
    private Presenter.ViewHolder gridViewHolder;
    private BaseRowItem currentItem;
    private boolean justLoaded = true;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = HorizontalGridBrowseBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.title.setText(R.string.channels);
        binding.statusText.setText(R.string.all_channels);
        binding.toolBar.setVisibility(View.GONE);

        binding.rowsFragment.post(() -> {
            if (binding == null) return;

            createGrid();
            buildAdapter();
            loadChannels();
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!justLoaded && adapter != null) {
            handler.postDelayed(() -> {
                if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;

                if (!adapter.ReRetrieveIfNeeded()) {
                    loadChannels();
                }
            }, 500);
        } else {
            justLoaded = false;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        binding = null;
        gridPresenter = null;
        gridView = null;
        gridViewHolder = null;
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        return keyProcessor.getValue().handleKey(keyCode, currentItem, requireActivity());
    }

    private void createGrid() {
        GridLayoutValues layoutValues = calculateGridLayoutValues();
        gridPresenter = new HorizontalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE);
        gridPresenter.setNumberOfRows(CHANNEL_ROWS);
        gridPresenter.setShadowEnabled(false);
        gridPresenter.setOnItemViewSelectedListener(selectedListener);
        gridPresenter.setOnItemViewClickedListener(clickedListener);

        gridViewHolder = gridPresenter.onCreateViewHolder(binding.rowsFragment);
        gridView = ((HorizontalGridPresenter.ViewHolder) gridViewHolder).getGridView();
        gridView.setGravity(Gravity.CENTER_VERTICAL);
        gridView.setHorizontalSpacing(layoutValues.horizontalSpacing);
        gridView.setVerticalSpacing(layoutValues.verticalSpacing);
        gridView.setPadding(
                layoutValues.horizontalPadding,
                layoutValues.verticalPadding,
                layoutValues.horizontalPadding,
                layoutValues.verticalPadding
        );
        gridView.setFocusable(true);
        gridView.setOnKeyListener(this);

        binding.rowsFragment.removeAllViews();
        binding.rowsFragment.addView(gridViewHolder.view);
    }

    private void buildAdapter() {
        GridLayoutValues layoutValues = calculateGridLayoutValues();
        adapter = new ItemRowAdapter(
                requireContext(),
                BrowsingUtils.createLiveTVChannelsRequest(),
                CHANNEL_CHUNK_SIZE,
                new ChannelCardPresenter(layoutValues.tileWidth, layoutValues.tileHeight),
                null
        );

        adapter.setRetrieveFinishedListener(() -> {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED) || binding == null || gridView == null) return;

            updateCounter(gridView.getSelectedPosition() + 1, adapter.getTotalItems());
            binding.statusText.setText(getString(R.string.lbl_tv_channel_status, adapter.getItemsLoaded(), adapter.getTotalItems()));
            gridView.setFocusable(adapter.getItemsLoaded() > 0);
            if (adapter.getItemsLoaded() > 0) {
                gridView.requestFocus();
            }
        });

        gridPresenter.onBindViewHolder(gridViewHolder, adapter);
    }

    private GridLayoutValues calculateGridLayoutValues() {
        int measuredWidth = binding.rowsFragment.getWidth();
        int measuredHeight = binding.rowsFragment.getHeight();
        int containerWidth = measuredWidth > 0 ? measuredWidth : getResources().getDisplayMetrics().widthPixels;
        int containerHeight = measuredHeight > 0 ? measuredHeight : getResources().getDisplayMetrics().heightPixels / 2;

        int horizontalSpacing = clamp(
                Math.round(containerWidth * 0.0035f),
                Utils.convertDpToPixel(requireContext(), MIN_GRID_SPACING_DP),
                Utils.convertDpToPixel(requireContext(), MAX_GRID_SPACING_DP)
        );
        int verticalSpacing = clamp(
                Math.round(containerHeight * 0.007f),
                Utils.convertDpToPixel(requireContext(), MIN_GRID_SPACING_DP),
                Utils.convertDpToPixel(requireContext(), MAX_GRID_SPACING_DP)
        );
        int horizontalPadding = clamp(
                Math.round(containerWidth * 0.008f),
                Utils.convertDpToPixel(requireContext(), MIN_GRID_PADDING_DP),
                Utils.convertDpToPixel(requireContext(), MAX_GRID_PADDING_DP)
        );
        int verticalPadding = clamp(
                Math.round(containerHeight * 0.008f),
                Utils.convertDpToPixel(requireContext(), MIN_GRID_PADDING_DP),
                Utils.convertDpToPixel(requireContext(), MAX_GRID_PADDING_DP)
        );

        int availableWidth = containerWidth - (horizontalPadding * 2);
        int availableHeight = containerHeight - (verticalPadding * 2) - (verticalSpacing * (CHANNEL_ROWS - 1));
        int tileWidth = availableWidth / 6;
        int tileHeight = availableHeight / CHANNEL_ROWS;

        return new GridLayoutValues(tileWidth, tileHeight, horizontalSpacing, verticalSpacing, horizontalPadding, verticalPadding);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class GridLayoutValues {
        final int tileWidth;
        final int tileHeight;
        final int horizontalSpacing;
        final int verticalSpacing;
        final int horizontalPadding;
        final int verticalPadding;

        GridLayoutValues(int tileWidth, int tileHeight, int horizontalSpacing, int verticalSpacing, int horizontalPadding, int verticalPadding) {
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.horizontalSpacing = horizontalSpacing;
            this.verticalSpacing = verticalSpacing;
            this.horizontalPadding = horizontalPadding;
            this.verticalPadding = verticalPadding;
        }
    }

    private void loadChannels() {
        if (adapter == null) return;

        adapter.Retrieve();
    }

    private void updateCounter(int position, int total) {
        if (binding == null) return;

        int safePosition = total > 0 ? Math.max(position, 1) : 0;
        binding.counter.setText(MessageFormat.format("{0} | {1}", safePosition, total));
    }

    private final OnItemViewSelectedListener selectedListener = new OnItemViewSelectedListener() {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item, RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (!(item instanceof BaseRowItem)) {
                currentItem = null;
                updateCounter(0, 0);
                return;
            }

            currentItem = (BaseRowItem) item;
            int selectedPosition = gridView != null ? gridView.getSelectedPosition() : adapter.indexOf(currentItem);
            updateCounter(selectedPosition + 1, adapter.getTotalItems());
            binding.statusText.setText(getString(R.string.lbl_tv_channel_status, adapter.getItemsLoaded(), adapter.getTotalItems()));
            adapter.loadMoreItemsIfNeeded(selectedPosition);
        }
    };

    private final OnItemViewClickedListener clickedListener = new OnItemViewClickedListener() {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item, RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (!(item instanceof BaseRowItem)) return;
            if (adapter == null) return;

            itemLauncher.getValue().launch((BaseRowItem) item, adapter, requireContext());
        }
    };
}
