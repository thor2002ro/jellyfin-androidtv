package org.jellyfin.androidtv.util

import android.content.Context

/**
 * Current (pixel) value as display pixels
 */
fun Int.dp(context: Context): Int = Utils.convertDpToPixel(context, this)

internal fun Int.coerceInOrStart(minimumValue: Int, maximumValue: Int) =
	if (minimumValue <= maximumValue) coerceIn(minimumValue, maximumValue) else minimumValue
