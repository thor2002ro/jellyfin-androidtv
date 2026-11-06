package org.jellyfin.androidtv.ui.card

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.shared.transition.ProfileLoginOrigin
import org.jellyfin.androidtv.util.MenuBuilder
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.popupMenu
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.showIfNotEmpty

@Composable
fun UserCard(
	image: @Composable () -> Unit,
	name: @Composable () -> Unit,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
	shape: Shape = CircleShape,
) {
	val focused by interactionSource.collectIsFocusedAsState()
	val context = LocalContext.current
	val accentColor = remember(context.theme) {
		Color(Utils.getThemeColor(context, android.R.attr.colorAccent))
	}

	// Keep names readable on the transparent background while using the theme accent for focus.
	val color = when {
		focused -> accentColor to JellyfinTheme.colorScheme.onInputFocused
		else -> JellyfinTheme.colorScheme.button to JellyfinTheme.colorScheme.onInput
	}
	val focusScale = LocalResources.current.getFraction(R.fraction.card_scale_focus, 1, 1)
	val focusDuration = integerResource(R.integer.card_scale_duration)
	val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(focusDuration), label = "UserCardFocusScale")

	Column(
		modifier = modifier
			.scale(scale)
			.focusable(interactionSource = interactionSource)
			.clickable(interactionSource = interactionSource, onClick = onClick, indication = null)
	) {
		Box(
			modifier = Modifier
				.aspectRatio(1f)
				.clip(shape)
				.background(JellyfinTheme.colorScheme.surface, shape)
				.border(if (focused) UserCardView.FOCUS_BORDER_WIDTH else 1.dp, color.first, shape)
		) {
			image()
		}

		Spacer(Modifier.height(UserCardView.NAME_SPACING))

		Box(
			modifier = Modifier
				.fillMaxWidth()
				.basicMarquee(
					iterations = if (focused) Int.MAX_VALUE else 0,
					initialDelayMillis = 0,
				),
			contentAlignment = Alignment.TopCenter,
		) {
			ProvideTextStyle(
				LocalTextStyle.current.copy(
					color = color.second,
				)
			) {
				name()
			}
		}
	}
}

class UserCardView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
	companion object {
		// Smaller avatars leave room for focus enlargement without shrinking the names underneath.
		internal val AVATAR_SIZE = 83.dp
		internal val FOCUS_BORDER_WIDTH = 3.dp
		internal val HORIZONTAL_PADDING = 10.dp
		internal val VERTICAL_PADDING = 12.dp
		internal val NAME_SPACING = 12.dp
		internal val NAME_LINE_HEIGHT = 20.sp
		internal val NAME_FONT_SIZE = 16.sp
		internal val USER_ICON_PADDING = 16.dp
		private val ADD_ACCOUNT_ICON_PADDING = 23.dp
		// Include the card's side padding so the grid and Compose agree on each column's width.
		internal val CARD_WIDTH = AVATAR_SIZE + HORIZONTAL_PADDING * 2
	}

	var name by mutableStateOf<String?>(null)
	var image by mutableStateOf<String?>(null)
	var imageIcon by mutableStateOf<Int?>(null)
	private var focused by mutableStateOf(false)
	private var avatarCoordinates: LayoutCoordinates? = null
	private var nameCoordinates: LayoutCoordinates? = null

	internal fun loginTransitionOrigin(): ProfileLoginOrigin? {
		val avatar = avatarCoordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: return null
		val label = nameCoordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: return null
		val density = Density(context)
		val scale = avatar.width / with(density) { AVATAR_SIZE.toPx() }
		return ProfileLoginOrigin(avatar, label, name.orEmpty(), image, with(density) { NAME_FONT_SIZE.toPx() } * scale)
			.takeIf { it.isUsable }
	}

	init {
		isFocusable = true
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		if (AndroidVersion.isAtLeastO) defaultFocusHighlightEnabled = false
	}

	fun setPopupMenu(init: MenuBuilder.() -> Unit) {
		setOnLongClickListener {
			popupMenu(context, this, init = init).showIfNotEmpty()
		}
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
		if (super.onKeyUp(keyCode, event)) return true

		// Menu key should show the popup menu
		if (event.keyCode == KeyEvent.KEYCODE_MENU) return performLongClick()

		return false
	}

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)

		focused = gainFocus
	}

	@Composable
	override fun Content() {
		val interactionSource = remember { MutableInteractionSource() }

		// The grid owns remote focus; forward it to Compose so the selected card gets its outline and zoom.
		val focusInteraction = remember { FocusInteraction.Focus() }
		LaunchedEffect(focused) {
			if (focused) interactionSource.emit(focusInteraction)
			else interactionSource.emit(FocusInteraction.Unfocus(focusInteraction))
		}

		UserCard(
			image = {
				val icon = imageIcon
				if (icon != null) Icon(
					imageVector = ImageVector.vectorResource(icon),
					contentDescription = null,
					modifier = Modifier.fillMaxSize().padding(ADD_ACCOUNT_ICON_PADDING),
				) else ProfilePicture(
					url = image,
					iconPadding = PaddingValues(USER_ICON_PADDING),
					modifier = Modifier.fillMaxSize().onGloballyPositioned { avatarCoordinates = it }
				)
			},
			name = {
				Text(
					text = name.orEmpty(),
					modifier = Modifier.onGloballyPositioned { nameCoordinates = it },
					maxLines = 1,
					fontSize = NAME_FONT_SIZE,
					fontWeight = FontWeight.Medium,
					lineHeight = NAME_LINE_HEIGHT,
				)
			},
			modifier = Modifier
				.padding(horizontal = HORIZONTAL_PADDING, vertical = VERTICAL_PADDING)
				.width(AVATAR_SIZE),
			interactionSource = interactionSource,
			// The outer View handles OK presses so grid selection and the profile menu keep working.
			onClick = {}
		)
	}
}
