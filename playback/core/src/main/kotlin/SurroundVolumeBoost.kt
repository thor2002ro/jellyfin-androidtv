package org.jellyfin.playback.core

fun surroundVolumeBoost(gainDb: Int, sourceChannels: Int?, pcmOutput: Boolean): Int =
	if (pcmOutput && sourceChannels != null && sourceChannels > 2) gainDb.coerceIn(0, 12) else 0
