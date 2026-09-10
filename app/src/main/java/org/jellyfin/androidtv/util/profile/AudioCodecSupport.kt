package org.jellyfin.androidtv.util.profile

import org.jellyfin.androidtv.constant.Codec

internal fun enabledPassthroughAudioCodecs(
	isAC3Enabled: Boolean,
	isEAC3Enabled: Boolean,
	isDTSEnabled: Boolean,
	isTrueHDEnabled: Boolean,
) = buildSet {
	add(Codec.Audio.AC4)
	if (isAC3Enabled) add(Codec.Audio.AC3)
	if (isEAC3Enabled) add(Codec.Audio.EAC3)
	if (isDTSEnabled) addAll(listOf(Codec.Audio.DCA, Codec.Audio.DTS))
	if (isTrueHDEnabled) addAll(listOf(Codec.Audio.MLP, Codec.Audio.TRUEHD))
}
