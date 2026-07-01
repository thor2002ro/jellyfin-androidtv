package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.UserDto

fun liveTvActionButtons(
	context: Context,
	user: UserDto?,
) = buildList {
	add(GridButton(
		LiveTvOption.LIVE_TV_CHANNELS_OPTION_ID,
		context.getString(R.string.channels),
		R.drawable.ic_tv,
	))
	add(GridButton(
		LiveTvOption.LIVE_TV_GUIDE_OPTION_ID,
		context.getString(R.string.lbl_live_tv_guide),
		R.drawable.ic_tv_guide,
	))
	add(GridButton(
		LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID,
		context.getString(R.string.lbl_recorded_tv),
		R.drawable.ic_tv_play,
	))

	if (Utils.canManageRecordings(user)) {
		add(GridButton(
			LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID,
			context.getString(R.string.lbl_schedule),
			R.drawable.ic_time,
		))
		add(GridButton(
			LiveTvOption.LIVE_TV_SERIES_OPTION_ID,
			context.getString(R.string.lbl_series),
			R.drawable.ic_tv_timer,
		))
	}
}
