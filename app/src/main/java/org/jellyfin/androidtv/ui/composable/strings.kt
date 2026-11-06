package org.jellyfin.androidtv.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.sdk.videoResolutionName

@Composable
fun getResolutionName(width: Int, height: Int, interlaced: Boolean = false): String =
	videoResolutionName(width, height, interlaced) ?: stringResource(R.string.lbl_sd)
