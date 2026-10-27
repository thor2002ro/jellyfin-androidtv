package org.jellyfin.androidtv.ui.shared.toolbar

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.JellyfinTheme

@Composable
internal fun HeaderBackdropLayout(headerModifier: Modifier, content: @Composable () -> Unit) {
	val rowsLayer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) rememberGraphicsLayer() else null
	Box(Modifier.fillMaxSize()) {
		Box(Modifier.fillMaxSize().then(
			if (rowsLayer != null) Modifier.drawWithContent {
				rowsLayer.record { this@drawWithContent.drawContent() }
				drawLayer(rowsLayer)
			} else Modifier
		)) { content() }
		Box(headerModifier) {
			if (rowsLayer != null) {
				Spacer(Modifier.matchParentSize().blur(16.dp).drawWithContent { drawLayer(rowsLayer) })
				Spacer(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
					JellyfinTheme.colorScheme.scrim.copy(alpha = 0.12f),
					Color.Transparent,
				))))
			} else {
				Spacer(Modifier.matchParentSize().background(JellyfinTheme.colorScheme.background.copy(alpha = 0.9f)))
			}
		}
	}
}
