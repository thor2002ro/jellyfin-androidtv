package org.jellyfin.androidtv.ui.livetv

import android.view.View
import android.widget.RelativeLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.LiveProgramDetailPopup
import org.jellyfin.androidtv.ui.RecordingIndicatorView
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItemSelectAction
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

class LiveTvCardActionHandler(
	private val fragment: Fragment,
	private val api: ApiClient,
	private val playbackHelper: PlaybackHelper,
	private val refreshFavorite: (UUID, Boolean) -> Unit,
) {
	private var detailPopup: LiveProgramDetailPopup? = null
	private val tvGuide = object : LiveTvGuide {
		override fun displayChannels(start: Int, max: Int) = Unit
		override fun getCurrentLocalStartDate(): LocalDateTime = LocalDateTime.now()
		override fun showProgramOptions() = Unit
		override fun setSelectedProgram(programView: RelativeLayout) = Unit
		override fun refreshFavorite(channelId: UUID, isFavorite: Boolean) = this@LiveTvCardActionHandler.refreshFavorite(channelId, isFavorite)
	}

	fun dismiss() {
		detailPopup?.dismiss()
	}

	fun onLongClick(item: Any?, anchor: View): Boolean {
		val rowItem = item as? BaseRowItem ?: return false
		val program = getProgramForOptions(rowItem) ?: return false
		val channel = getChannelForOptions(rowItem)

		showProgramOptions(program, channel, anchor)
		return true
	}

	private fun getProgramForOptions(rowItem: BaseRowItem): BaseItemDto? {
		val item = rowItem.baseItem ?: return null

		return when {
			rowItem.baseRowType == BaseRowType.LiveTvProgram &&
				rowItem.selectAction == BaseRowItemSelectAction.Play -> item

			rowItem.baseRowType == BaseRowType.LiveTvChannel ->
				item.currentProgram?.withChannelFallback(item) ?: item.withChannelFallback(item)

			else -> null
		}
	}

	private fun getChannelForOptions(rowItem: BaseRowItem): BaseItemDto? {
		if (rowItem.baseRowType != BaseRowType.LiveTvChannel) return null

		return rowItem.baseItem
	}

	private fun showProgramOptions(program: BaseItemDto, favoriteChannel: BaseItemDto?, anchor: View) {
		if (favoriteChannel != null || program.channelId == null) {
			showProgramOptionsWithChannel(program, favoriteChannel, anchor)
			return
		}

		val channelId = program.channelId ?: return
		fragment.viewLifecycleOwner.lifecycleScope.launch {
			val loadedChannel = try {
				withContext(Dispatchers.IO) {
					api.liveTvApi.getChannel(channelId).content
				}
			} catch (error: ApiClientException) {
				Timber.w(error, "Unable to load Live TV channel $channelId for program actions")
				null
			}

			if (!fragment.isAdded || !anchor.isAttachedToWindow || !anchor.hasFocus() || anchor.tag != program.id) return@launch
			showProgramOptionsWithChannel(program, loadedChannel, anchor)
		}
	}

	private fun showProgramOptionsWithChannel(program: BaseItemDto, favoriteChannel: BaseItemDto?, anchor: View) {
		val popupWidth = getProgramPopupWidth()
		val popupProgram = favoriteChannel?.let { program.withChannelFallback(it) } ?: program

		detailPopup?.dismiss()
		detailPopup = LiveProgramDetailPopup(
			fragment.requireActivity(),
			fragment,
			tvGuide,
			popupWidth,
			object : EmptyResponse(fragment.lifecycle) {
				override fun onResponse() {
					if (!isActive) return
					popupProgram.channelId?.let { channelId ->
						playbackHelper.retrieveAndPlay(channelId, false, fragment.requireContext())
					}
				}
			},
		).also { popup ->
			popup.setContent(popupProgram, recordingIndicator(popupProgram, favoriteChannel), favoriteChannel)
			popup.show(anchor, 0, 0)
		}
	}

	private fun recordingIndicator(program: BaseItemDto, favoriteChannel: BaseItemDto?) = object : RecordingIndicatorView {
		private var refreshed = false

		override fun setRecTimer(id: String?) = refreshOnce()

		override fun setRecSeriesTimer(id: String?) = refreshOnce()

		private fun refreshOnce() {
			if (refreshed) return
			refreshed = true
			(program.channelId ?: favoriteChannel?.id)?.let { channelId ->
				refreshFavorite(channelId, favoriteChannel?.userData?.isFavorite == true)
			}
		}
	}

	private fun BaseItemDto.withChannelFallback(channel: BaseItemDto) = copy(
		channelId = channelId ?: channel.id,
		channelName = channelName ?: channel.name,
		channelNumber = channelNumber ?: channel.number,
	)

	private fun getProgramPopupWidth(): Int {
		val horizontalMargin = Utils.convertDpToPixel(fragment.requireContext(), PROGRAM_DETAIL_POPUP_HORIZONTAL_MARGIN_DP)
		return minOf(
			Utils.convertDpToPixel(fragment.requireContext(), PROGRAM_DETAIL_POPUP_WIDTH_DP),
			(fragment.resources.displayMetrics.widthPixels - horizontalMargin * 2).coerceAtLeast(horizontalMargin),
		)
	}

	private companion object {
		const val PROGRAM_DETAIL_POPUP_WIDTH_DP = 600
		const val PROGRAM_DETAIL_POPUP_HORIZONTAL_MARGIN_DP = 48
	}
}
