package org.jellyfin.androidtv.ui.shared.transition

import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.card.UserCardView
import org.jellyfin.androidtv.util.Utils
import kotlin.math.roundToInt

private const val EXTRA_PROFILE_LOGIN = "profile_login_transition"
private const val ANIMATION_DURATION_MS = 700
// Fall back to Home if its toolbar or rows cannot finish preparing.
private const val HOME_READY_TIMEOUT_MS = 2_000L

internal data class ProfileLoginOrigin(
	val avatarBounds: Rect,
	val nameBounds: Rect,
	val name: String,
	val imageUrl: String?,
	val nameFontSizePx: Float,
) {
	val isUsable: Boolean get() = avatarBounds.isUsable && nameBounds.isUsable &&
		name.isNotBlank() && nameFontSizePx.isFinite() && nameFontSizePx > 0f

	// Pass only geometry and presentation data; authentication stays in the existing repositories.
	fun writeTo(intent: Intent) {
		intent.putExtra(EXTRA_PROFILE_LOGIN, Bundle().apply {
			putFloatArray("avatar", avatarBounds.toFloatArray())
			putFloatArray("label", nameBounds.toFloatArray())
			putString("name", name)
			putString("image", imageUrl)
			putFloat("font_size", nameFontSizePx)
		})
	}

	companion object {
		fun readFrom(intent: Intent): ProfileLoginOrigin? {
			val data = intent.getBundleExtra(EXTRA_PROFILE_LOGIN) ?: return null
			return ProfileLoginOrigin(
				avatarBounds = data.getFloatArray("avatar").toBounds() ?: return null,
				nameBounds = data.getFloatArray("label").toBounds() ?: return null,
				name = data.getString("name") ?: return null,
				imageUrl = data.getString("image"),
				nameFontSizePx = data.getFloat("font_size"),
			).takeIf { it.isUsable }
		}
	}
}

private val Rect.isUsable: Boolean get() = width.isFinite() && height.isFinite() && width > 0f && height > 0f &&
	left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
private fun Rect.toFloatArray() = floatArrayOf(left, top, right, bottom)
private fun FloatArray?.toBounds(): Rect? =
	if (this != null && size == 4) Rect(this[0], this[1], this[2], this[3]) else null

internal class ProfileLoginAnimation(val origin: ProfileLoginOrigin) {
	var avatarTarget by mutableStateOf<Rect?>(null)
	var nameTarget by mutableStateOf<Rect?>(null)
	var nameFontSizeTargetPx by mutableFloatStateOf(0f)
	var progress by mutableFloatStateOf(0f)
	var homeRowsReady by mutableStateOf(false)
	val isReady: Boolean get() = homeRowsReady && avatarTarget?.isUsable == true && nameTarget?.isUsable == true &&
		nameFontSizeTargetPx.isFinite() && nameFontSizeTargetPx > 0f
}

@Composable
internal fun ProfileLoginOverlay(animation: ProfileLoginAnimation, onFinished: () -> Unit) {
	val density = LocalDensity.current
	val context = LocalContext.current
	val accent = remember(context.theme) { Color(Utils.getThemeColor(context, android.R.attr.colorAccent)) }
	var windowOffset by remember { mutableStateOf(Offset.Zero) }
	val origin = animation.origin
	val progress = animation.progress
	val avatar = lerp(origin.avatarBounds, animation.avatarTarget ?: origin.avatarBounds, progress).translate(-windowOffset)
	val label = lerp(origin.nameBounds, animation.nameTarget ?: origin.nameBounds, progress).translate(-windowOffset)
	val fontSize = origin.nameFontSizePx + (animation.nameFontSizeTargetPx - origin.nameFontSizePx) * progress
	val sourceScale = origin.avatarBounds.width / with(density) { UserCardView.AVATAR_SIZE.toPx() }

	// Start after native row setup; its requests keep loading while the profile moves.
	LaunchedEffect(animation) {
		try {
			val ready = withTimeoutOrNull(HOME_READY_TIMEOUT_MS) { snapshotFlow { animation.isReady }.first { it } }
			if (ready == true) {
				Animatable(0f).animateTo(1f, tween(ANIMATION_DURATION_MS, easing = FastOutSlowInEasing)) {
					animation.progress = value
				}
			}
		} finally {
			onFinished()
		}
	}

	Box(
		Modifier.fillMaxSize()
			.onGloballyPositioned { windowOffset = it.positionInWindow() }
	) {
		Box(
			Modifier
				.offset { IntOffset(avatar.left.roundToInt(), avatar.top.roundToInt()) }
				.size(with(density) { avatar.width.toDp() }, with(density) { avatar.height.toDp() })
				.clip(CircleShape)
				.background(JellyfinTheme.colorScheme.surface.copy(alpha = 1f - progress))
				.border(UserCardView.FOCUS_BORDER_WIDTH * sourceScale, accent.copy(alpha = 1f - progress), CircleShape)
		) {
			ProfilePicture(
				url = origin.imageUrl,
				iconPadding = PaddingValues(UserCardView.USER_ICON_PADDING * sourceScale * (1f - progress)),
				modifier = Modifier.fillMaxSize(),
			)
		}
		Text(
			text = origin.name,
			modifier = Modifier
				.offset { IntOffset(label.left.roundToInt(), label.top.roundToInt()) }
				.size(with(density) { label.width.toDp() }, with(density) { label.height.toDp() }),
			fontSize = with(density) { fontSize.toSp() },
			lineHeight = with(density) { label.height.toSp() },
			fontWeight = FontWeight((FontWeight.Medium.weight +
				(FontWeight.Normal.weight - FontWeight.Medium.weight) * progress).roundToInt()),
			color = JellyfinTheme.colorScheme.onBackground,
			textAlign = TextAlign.Center,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
