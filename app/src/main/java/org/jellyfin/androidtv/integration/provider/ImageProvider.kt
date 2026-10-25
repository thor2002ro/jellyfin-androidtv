package org.jellyfin.androidtv.integration.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.error
import kotlinx.coroutines.runBlocking
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.AndroidVersion
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException
import java.io.OutputStream

class ImageProvider : ContentProvider() {
	private val imageLoader by inject<ImageLoader>()

	override fun onCreate(): Boolean = true

	override fun getType(uri: Uri) = null
	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
	override fun insert(uri: Uri, values: ContentValues?) = null
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
		val src = requireNotNull(uri.getQueryParameter("src")).toUri()

		return openPipeHelper(uri, "image/webp", null, src) { write, _, _, _, source ->
			try {
				writeImage(requireNotNull(source), ParcelFileDescriptor.AutoCloseOutputStream(write))
			} catch (_: IOException) {
				// The consumer can close its pipe before the image has finished writing.
			} catch (exception: Exception) {
				Timber.w(exception, "Unable to write Android TV artwork")
			}
		}
	}

	internal fun writeImage(source: Uri, outputStream: OutputStream) {
		outputStream.use {
			val result = runBlocking {
				imageLoader.execute(ImageRequest.Builder(context!!).apply {
					data(source)
					size(MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)
					allowHardware(false)
					error(R.drawable.placeholder_icon)
				}.build())
			}
			result.image?.let { image -> writeDrawable(image.asDrawable(context!!.resources), outputStream) }
		}
	}

	private fun writeDrawable(
		drawable: Drawable,
		outputStream: OutputStream
	) {
		@Suppress("DEPRECATION")
		val format = when {
			AndroidVersion.isAtLeastR -> Bitmap.CompressFormat.WEBP_LOSSY
			else -> Bitmap.CompressFormat.WEBP
		}

		drawable.toBitmap().compress(format, COMPRESSION_QUALITY, outputStream)
	}

	companion object {
		internal const val MAX_IMAGE_SIZE = 512
		private const val COMPRESSION_QUALITY = 95

		/**
		 * Get a [Uri] that uses the [ImageProvider] to load an image. The input should be a valid
		 * Jellyfin image URL created using the SDK.
		 */
		fun getImageUri(src: String): Uri = Uri.Builder()
			.scheme("content")
			.authority("${BuildConfig.APPLICATION_ID}.integration.provider.ImageProvider")
			.appendQueryParameter("src", src)
			.appendQueryParameter("v", BuildConfig.VERSION_NAME)
			.build()
	}
}
