package org.jellyfin.androidtv.util.profile

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat

// Values from Display.HdrCapabilities. Kept local to avoid minSdk inlined API warnings.
internal const val DISPLAY_HDR_TYPE_DOLBY_VISION = 1
internal const val DISPLAY_HDR_TYPE_HDR10 = 2
internal const val DISPLAY_HDR_TYPE_HLG = 3
internal const val DISPLAY_HDR_TYPE_HDR10_PLUS = 4

internal fun getSupportedDisplayHdrTypes(context: Context): Set<Int> {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptySet()

	val display = ContextCompat.getDisplayOrDefault(context)
	@Suppress("DEPRECATION")
	return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		display.mode.supportedHdrTypes.toSet()
	} else {
		display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
	}
}
