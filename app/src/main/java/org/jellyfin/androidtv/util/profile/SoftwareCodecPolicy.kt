package org.jellyfin.androidtv.util.profile

import org.jellyfin.androidtv.preference.constant.LibMPVDecoder
import org.jellyfin.androidtv.preference.constant.PlaybackBackend

internal fun softwareCodecsEnabledForProfile(
	backend: PlaybackBackend,
	mpvDecoder: LibMPVDecoder,
	softwareCodecsEnabled: Boolean,
): Boolean = softwareCodecsEnabled && when (backend) {
	PlaybackBackend.MPV -> mpvDecoder == LibMPVDecoder.SOFTWARE
	else -> true
}
