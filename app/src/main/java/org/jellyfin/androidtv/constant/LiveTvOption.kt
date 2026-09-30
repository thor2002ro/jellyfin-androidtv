package org.jellyfin.androidtv.constant

import java.util.UUID

object LiveTvOption {
	const val LIVE_TV_DISPLAY_PREFERENCES_ID = "livetv"
	val LIVE_TV_VIEW_ID: UUID = UUID.nameUUIDFromBytes("org.jellyfin.androidtv.livetv".toByteArray(Charsets.UTF_8))

	const val LIVE_TV_CHANNELS_OPTION_ID = 500
	const val LIVE_TV_GUIDE_OPTION_ID = 1000
	const val LIVE_TV_RECORDINGS_OPTION_ID = 2000
	const val LIVE_TV_SCHEDULE_OPTION_ID = 4000
	const val LIVE_TV_SERIES_OPTION_ID = 5000
}
