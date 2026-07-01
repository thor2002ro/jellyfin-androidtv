package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.graphics.Typeface
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.SystemPreferences
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.TextItemPresenter
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Function

object TvManager {
	private var allChannels: MutableList<BaseItemDto>? = null
	private var channelIds: Array<UUID> = emptyArray()
	private var programsByChannel = HashMap<UUID?, ArrayList<BaseItemDto>>()
	private var needLoadTime: LocalDateTime? = null
	private var forceReload = false

	@JvmStatic
	fun getLastLiveTvChannel(): UUID? {
		val systemPreferences = KoinJavaComponent.get<SystemPreferences>(SystemPreferences::class.java)
		return Utils.uuidOrNull(systemPreferences[SystemPreferences.liveTvLastChannel])
	}

	@JvmStatic
	fun setLastLiveTvChannel(id: UUID) {
		val systemPreferences = KoinJavaComponent.get<SystemPreferences>(SystemPreferences::class.java)
		systemPreferences[SystemPreferences.liveTvPrevChannel] = systemPreferences[SystemPreferences.liveTvLastChannel]
		systemPreferences[SystemPreferences.liveTvLastChannel] = id.toString()
		updateLastPlayedDate(id)
		fillChannelIds()
	}

	@JvmStatic
	fun getPrevLiveTvChannel(): UUID? {
		val systemPreferences = KoinJavaComponent.get<SystemPreferences>(SystemPreferences::class.java)
		return Utils.uuidOrNull(systemPreferences[SystemPreferences.liveTvPrevChannel])
	}

	@JvmStatic
	fun getAllChannels(): List<BaseItemDto>? = allChannels

	@JvmStatic
	fun forceReload() {
		forceReload = true
	}

	@JvmStatic
	fun shouldForceReload() = forceReload

	@JvmStatic
	fun getAllChannelsIndex(id: UUID?): Int {
		val channels = allChannels ?: return -1
		if (id == null) return -1

		for (i in channels.indices) {
			if (channels[i].id == id) return i
		}
		return -1
	}

	@JvmStatic
	fun getChannel(ndx: Int): BaseItemDto = requireNotNull(allChannels)[ndx]

	@JvmStatic
	fun updateLastPlayedDate(channelId: UUID) {
		val channels = allChannels ?: return
		val index = getAllChannelsIndex(channelId)
		if (index >= 0) {
			channels[index] = channels[index].copyWithLastPlayedDate(LocalDateTime.now())
		}
	}

	@JvmStatic
	fun loadAllChannels(fragment: Fragment, outerResponse: Function<Int, Void?>) {
		loadLiveTvChannels(fragment) { channels ->
			if (channels != null) {
				allChannels = ArrayList(channels)
				outerResponse.apply(fillChannelIds())
			} else {
				outerResponse.apply(0)
			}
		}
	}

	private fun fillChannelIds(): Int {
		var selectedIndex = 0
		val channels = allChannels ?: return selectedIndex
		channelIds = Array(channels.size) { index -> channels[index].id }

		val last = getLastLiveTvChannel() ?: return selectedIndex
		channels.forEachIndexed { index, channel ->
			if (channel.id == last) selectedIndex = index + 1
		}

		return selectedIndex
	}

	@JvmStatic
	fun getProgramsAsync(
		fragment: Fragment,
		startNdx: Int,
		endNdx: Int,
		startTime: LocalDateTime,
		endTime: LocalDateTime,
		outerResponse: EmptyResponse,
	) {
		val startTimeRounded = startTime
			.withMinute(if (startTime.minute >= 30) 30 else 0)
			.withSecond(0)
			.withNano(0)
		val endTimeRounded = endTime.minusSeconds(1)
		val endExclusive = if (endNdx > channelIds.size) channelIds.size else endNdx + 1

		getPrograms(
			fragment = fragment,
			channelIds = channelIds.copyOfRange(startNdx, endExclusive),
			startTime = startTimeRounded,
			endTime = endTimeRounded,
		) { programs ->
			if (programs != null) {
				Timber.v("About to build Live TV programs dictionary")
				buildProgramsDict(programs, startTime)
				Timber.d("Live TV programs retrieval finished")
			}

			outerResponse.onResponse()
		}

		Timber.v("About to get Live TV programs")
	}

	private fun buildProgramsDict(programs: Collection<BaseItemDto>, startTime: LocalDateTime) {
		programsByChannel = HashMap()
		for (program in programs) {
			val id = program.channelId
			if (!programsByChannel.containsKey(id)) {
				programsByChannel[id] = ArrayList()
			}
			if (program.endDate?.isAfter(startTime) == true) {
				programsByChannel[id]?.add(program)
			}
		}
		needLoadTime = startTime.plusMinutes(29)
	}

	@JvmStatic
	@JvmOverloads
	fun getProgramsForChannel(channelId: UUID, filters: GuideFilters? = null): List<BaseItemDto> {
		val results = programsByChannel[channelId] ?: return ArrayList()
		val passes = filters == null || !filters.any()
		if (passes) return results

		return if (results.any { program -> filters.passesFilter(program) }) {
			results
		} else {
			ArrayList()
		}
	}

	@JvmStatic
	fun setTimelineRow(context: Context, timelineRow: LinearLayout, program: BaseItemDto) {
		timelineRow.removeAllViews()
		val local = program.startDate ?: return

		timelineRow.addView(TextView(context).apply {
			text = context.resources.getString(R.string.lbl_on)
		})
		timelineRow.addView(TextView(context).apply {
			text = program.channelName
			setTypeface(null, Typeface.BOLD)
			setTextColor(context.resources.getColor(android.R.color.holo_blue_light))
		})
		timelineRow.addView(TextView(context).apply {
			text = StringBuilder()
				.append(TimeUtils.getFriendlyDate(context, local))
				.append(" @ ")
				.append(context.getTimeFormatter().format(local))
				.append(" (")
				.append(
					DateUtils.getRelativeTimeSpanString(
						local.toInstant(ZoneOffset.UTC).toEpochMilli(),
						Instant.now().toEpochMilli(),
						0,
					)
				)
				.append(")")
		})
	}

	@JvmStatic
	fun setFocusParams(currentRow: LinearLayout, otherRow: LinearLayout, up: Boolean) {
		for (currentRowNdx in 0 until currentRow.childCount) {
			val cell = currentRow.getChildAt(currentRowNdx) as ProgramGridCell
			val otherCell = getOtherCell(otherRow, cell)
			if (otherCell != null) {
				if (up) {
					cell.nextFocusUpId = otherCell.id
				} else {
					cell.nextFocusDownId = otherCell.id
				}
			}
		}
	}

	private fun getOtherCell(otherRow: LinearLayout, cell: ProgramGridCell): ProgramGridCell? {
		for (otherRowNdx in 0 until otherRow.childCount) {
			val otherCell = otherRow.getChildAt(otherRowNdx) as ProgramGridCell
			val otherEnd = otherCell.getProgram().endDate
			val cellStart = cell.getProgram().startDate
			if (otherEnd != null && cellStart != null && otherEnd.isAfter(cellStart)) {
				return otherCell
			}
		}
		return null
	}

	@JvmStatic
	fun getScheduleRowsAsync(
		fragment: Fragment,
		seriesTimerId: String?,
		presenter: Presenter,
		rowAdapter: MutableObjectAdapter<Row>,
	) {
		getScheduleRows(fragment, seriesTimerId) { timerMap ->
			if (!fragment.isAdded) return@getScheduleRows

			val context = fragment.requireContext()
			if (timerMap == null || timerMap.isEmpty()) {
				val emptyRow = ArrayObjectAdapter(TextItemPresenter())
				emptyRow.add(context.getString(R.string.lbl_no_items))
				rowAdapter.add(ListRow(HeaderItem(context.getString(R.string.lbl_empty)), emptyRow))
				return@getScheduleRows
			}

			for (entry in timerMap.entries) {
				addRow(context, entry.value, presenter, rowAdapter)
			}
		}
	}

	private fun addRow(
		context: Context,
		timers: List<BaseItemDto>,
		presenter: Presenter,
		rowAdapter: MutableObjectAdapter<Row>,
	) {
		val scheduledAdapter = ItemRowAdapter(context, timers, presenter, rowAdapter, true)
		scheduledAdapter.Retrieve()
		val scheduleRow = ListRow(
			HeaderItem(TimeUtils.getFriendlyDate(context, timers[0].startDate, true)),
			scheduledAdapter,
		)
		rowAdapter.add(scheduleRow)
	}

	@JvmStatic
	fun getChannelByID(channelID: UUID?): BaseItemDto? {
		val channelIndex = getAllChannelsIndex(channelID)
		return if (channelIndex >= 0) allChannels?.get(channelIndex) else null
	}
}
