package org.jellyfin.androidtv.ui.card;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.databinding.ViewCardChannelBinding;
import org.jellyfin.androidtv.util.DateTimeExtensionsKt;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.koin.java.KoinJavaComponent;

import java.time.Duration;
import java.time.LocalDateTime;

public class ChannelCardView extends FrameLayout {
    private static final float BASE_CARD_WIDTH_DP = 260f;
    private static final float BASE_CARD_HEIGHT_DP = 128f;
    private static final float MIN_CONTENT_SCALE = 0.35f;
    private static final float MAX_CONTENT_SCALE = 1.4f;

    private final ViewCardChannelBinding binding = ViewCardChannelBinding.inflate(LayoutInflater.from(getContext()), this, true);
    private final ImageHelper imageHelper = KoinJavaComponent.get(ImageHelper.class);
    private float appliedScale = 0f;

    public ChannelCardView(Context context) {
        super(context);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyContentScale(width, height);
    }

    public void setItem(final BaseItemDto channel) {
        if (channel == null) return;
        binding.channelNumber.setText(channel.getNumber() != null ? channel.getNumber() : "");
        binding.channelName.setText(channel.getName());
        binding.channelImage.load(
                imageHelper.getPrimaryImageUrl(channel, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT),
                null,
                null,
                0.0,
                0
        );

        boolean isFavorite = channel.getUserData() != null && channel.getUserData().isFavorite();
        binding.favImage.setVisibility(isFavorite ? View.VISIBLE : View.GONE);

        BaseItemDto program = channel.getCurrentProgram();
        if (program != null) {
            updateDisplay(program);
            updateRecordingIndicator(program);
        } else {
            binding.program.setText(R.string.no_program_data);
            binding.time.setText("");
            binding.progress.setProgress(0);
            binding.recIndicator.setVisibility(View.GONE);
        }
    }

    private void updateRecordingIndicator(BaseItemDto program) {
        if (program.getSeriesTimerId() != null) {
            binding.recIndicator.setImageResource(program.getTimerId() != null
                    ? R.drawable.ic_record_series_red : R.drawable.ic_record_series);
            binding.recIndicator.setVisibility(View.VISIBLE);
        } else if (program.getTimerId() != null) {
            binding.recIndicator.setImageResource(R.drawable.ic_record_red);
            binding.recIndicator.setVisibility(View.VISIBLE);
        } else {
            binding.recIndicator.setVisibility(View.GONE);
        }
    }

    private void updateDisplay(BaseItemDto program) {
        binding.program.setText(program.getName());
        if (program.getStartDate() != null && program.getEndDate() != null) {
            binding.time.setText(new StringBuilder()
                    .append(DateTimeExtensionsKt.getTimeFormatter(getContext()).format(program.getStartDate()))
                    .append("-")
                    .append(DateTimeExtensionsKt.getTimeFormatter(getContext()).format(program.getEndDate()))
            );

            if (program.getStartDate().isBefore(LocalDateTime.now()) && program.getEndDate().isAfter(LocalDateTime.now())) {
                Duration duration = Duration.between(program.getStartDate(), program.getEndDate());
                Duration progress = Duration.between(program.getStartDate(), LocalDateTime.now());
            
                binding.progress.setProgress((int) ((progress.getSeconds() / (double) duration.getSeconds()) * 100));
            } else {
                binding.progress.setProgress(0);
            }          
        } else {
            binding.time.setText("");
            binding.progress.setProgress(0);
        }
    }

    private void applyContentScale(int width, int height) {
        if (width <= 0 || height <= 0) return;

        float baseWidth = dp(BASE_CARD_WIDTH_DP);
        float baseHeight = dp(BASE_CARD_HEIGHT_DP);
        float scale = Math.min(width / baseWidth, height / baseHeight);
        scale = Math.max(MIN_CONTENT_SCALE, Math.min(MAX_CONTENT_SCALE, scale));
        if (Math.abs(scale - appliedScale) < 0.01f) return;

        appliedScale = scale;

        int padding = scaledDp(10f, scale);
        binding.getRoot().setPadding(padding, padding, padding, padding);

        setSize(binding.favImage, 14f, 14f, scale);
        setSize(binding.recIndicator, 14f, 14f, scale);
        setMargins(binding.recIndicator, 4f, 4f, 0f, 0f, scale);

        setSize(binding.channelImage, 62f, 40f, scale);
        setSize(binding.channelNumber, 62f, 0f, scale);
        setMargins(binding.channelNumber, 0f, 4f, 0f, 0f, scale);

        setMargins(binding.channelName, 10f, 0f, 0f, 0f, scale);
        setTextSize(binding.channelNumber, 13f, scale);
        setTextSize(binding.channelName, 16f, scale);
        setTextSize(binding.program, 13f, scale);
        setTextSize(binding.time, 13f, scale);

        ViewGroup.LayoutParams progressParams = binding.progress.getLayoutParams();
        progressParams.height = scaledDp(5f, scale);
        binding.progress.setLayoutParams(progressParams);
        setMargins(binding.progress, 0f, 4f, 0f, 0f, scale);
        binding.progress.setMinimumHeight(progressParams.height);
    }

    private void setSize(View view, float widthDp, float heightDp, float scale) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (widthDp > 0f) params.width = scaledDp(widthDp, scale);
        if (heightDp > 0f) params.height = scaledDp(heightDp, scale);
        view.setLayoutParams(params);
    }

    private void setMargins(View view, float startDp, float topDp, float endDp, float bottomDp, float scale) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof RelativeLayout.LayoutParams)) return;

        RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) params;
        layoutParams.setMarginStart(scaledDp(startDp, scale));
        layoutParams.topMargin = scaledDp(topDp, scale);
        layoutParams.setMarginEnd(scaledDp(endDp, scale));
        layoutParams.bottomMargin = scaledDp(bottomDp, scale);
        view.setLayoutParams(layoutParams);
    }

    private void setTextSize(android.widget.TextView view, float sp, float scale) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(6f, sp * scale));
    }

    private int scaledDp(float value, float scale) {
        if (value <= 0f) return 0;
        return Math.max(1, Math.round(dp(value) * scale));
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }
}
