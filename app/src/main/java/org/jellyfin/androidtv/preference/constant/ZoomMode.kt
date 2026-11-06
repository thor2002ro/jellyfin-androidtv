package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class ZoomMode(
	override val nameRes: Int,
) : PreferenceEnum {
	/**
	 * Selects fit or stretch from the first valid video geometry.
	 */
	AUTO(R.string.auto),

	/**
	 * Sets the zoom mode to normal (fit).
	 */
	FIT(R.string.lbl_fit),

	/**
	 * Sets the zoom mode to auto crop.
	 */
	AUTO_CROP(R.string.lbl_auto_crop),

	/**
	 * Expands the fitted video horizontally without cropping.
	 */
	HORIZONTAL_STRETCH(R.string.lbl_horizontal_stretch),

	/**
	 * Expands the fitted video vertically without cropping.
	 */
	VERTICAL_STRETCH(R.string.lbl_vertical_stretch),

	/**
	 * Sets the zoom mode to stretch.
	 */
	STRETCH(R.string.lbl_stretch),
}
