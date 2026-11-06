package org.jellyfin.androidtv.ui.player.photo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

@Composable
fun PhotoPlayerContent(
	item: BaseItemDto?,
) {
	val resources = LocalResources.current

	AnimatedContent(
		targetState = item,
		transitionSpec = {
			fadeIn() togetherWith fadeOut()
		}
	) { item ->
		val image = item?.itemImages[ImageType.PRIMARY]

		AsyncImage(
			image = image,
			maxWidth = resources.displayMetrics.widthPixels,
			maxHeight = resources.displayMetrics.heightPixels,
			aspectRatio = image?.aspectRatio ?: 1f,
			modifier = Modifier
				.fillMaxSize()
		)
	}
}
