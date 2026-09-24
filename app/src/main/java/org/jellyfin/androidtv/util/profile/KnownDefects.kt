package org.jellyfin.androidtv.util.profile

import android.os.Build

/**
 * List of device models with unreported Dolby Vision Profile 7 support.
 */
private val modelsWithUnreportedDoviProfile7Support = listOf(
	"SHIELD Android TV", // NVIDIA Shield TV Pro 2019 (mdarcy)
)

object KnownDefects {
	val unreportedDoviProfile7Support = Build.MODEL in modelsWithUnreportedDoviProfile7Support
}
