package org.jellyfin.androidtv.integration.provider

import android.content.ContentProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import coil3.Image
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class ImageProviderTests : FunSpec({
	beforeTest {
		mockkStatic(Uri::class)
		mockkStatic("coil3.Image_androidKt", "androidx.core.graphics.drawable.DrawableKt")
	}
	afterTest {
		unmockkStatic(Uri::class)
		unmockkStatic("coil3.Image_androidKt", "androidx.core.graphics.drawable.DrawableKt")
	}

	test("launcher artwork schedules a pipe worker and its encoder loads a bounded software image") {
		val fixture = imageProviderFixture("https://server.test/Items/id/Images/Primary")
		val image = mockk<Image>()
		val drawable = mockk<Drawable> {
			every { intrinsicWidth } returns 320
			every { intrinsicHeight } returns 180
		}
		val bitmap = mockk<Bitmap>()
		val request = slot<ImageRequest>()
		val caller = Thread.currentThread()
		var compressionThread: Thread? = null
		coEvery { fixture.loader.execute(capture(request)) } returns mockk<SuccessResult> {
			every { this@mockk.image } returns image
		}
		every { image.asDrawable(any()) } returns drawable
		every { drawable.toBitmap(any(), any(), any()) } returns bitmap
		every { bitmap.compress(any(), any(), any()) } answers {
			compressionThread = Thread.currentThread()
			true
		}

		fixture.provider.openFile(fixture.uri, "r") shouldBe fixture.read
		coVerify(exactly = 0) { fixture.loader.execute(any()) }
		fixture.worker.isCaptured shouldBe true
		fixture.runWriter()

		request.captured.data shouldBe fixture.source
		request.captured.sizeResolver.size() shouldBe Size(512, 512)
		request.captured.allowHardware shouldBe false
		request.captured.target shouldBe null
		(compressionThread != null && compressionThread != caller) shouldBe true
		verify(exactly = 1) { fixture.output.close() }
	}

	test("resource fallback URIs remain valid request data and missing error artwork closes the writer") {
		val fixture = imageProviderFixture("android.resource://org.jellyfin.androidtv/drawable/tile_land_tv")
		val request = slot<ImageRequest>()
		coEvery { fixture.loader.execute(capture(request)) } returns mockk<ErrorResult> {
			every { image } returns null
		}

		fixture.provider.openFile(fixture.uri, "r")
		fixture.runWriter()

		request.captured.data shouldBe fixture.source
		verify(exactly = 1) { fixture.output.close() }
	}

	for (failure in listOf(IOException("reader closed"), CancellationException("request cancelled"))) {
		test("${failure.javaClass.simpleName} during image loading closes the writer") {
			val fixture = imageProviderFixture("https://server.test/artwork")
			coEvery { fixture.loader.execute(any()) } throws failure

			fixture.provider.openFile(fixture.uri, "r")
			shouldThrow<ExecutionException> { fixture.runWriter() }.cause shouldBe failure

			verify(exactly = 1) { fixture.output.close() }
		}
	}
})

private class ImageProviderFixture(
	val provider: ImageProvider,
	val loader: ImageLoader,
	val uri: Uri,
	val source: Uri,
	val read: ParcelFileDescriptor,
	val output: OutputStream,
	val worker: io.mockk.CapturingSlot<ContentProvider.PipeDataWriter<Uri>>,
) {
	fun runWriter() {
		CompletableFuture.runAsync {
			provider.writeImage(source, output)
		}.get(5, TimeUnit.SECONDS)
	}
}

private fun imageProviderFixture(url: String): ImageProviderFixture {
	val context = mockk<Context>(relaxed = true)
	val loader = mockk<ImageLoader>(relaxed = true)
	val provider = spyk(ImageProvider(), recordPrivateCalls = true)
	val source = mockk<Uri>()
	val uri = mockk<Uri> { every { getQueryParameter("src") } returns url }
	val read = mockk<ParcelFileDescriptor>()
	val output = mockk<OutputStream>(relaxed = true)
	val worker = slot<ContentProvider.PipeDataWriter<Uri>>()
	every { Uri.parse(url) } returns source
	every { provider.context } returns context
	ImageProvider::class.java.getDeclaredField("imageLoader\$delegate").apply {
		isAccessible = true
		set(provider, lazyOf(loader))
	}
	every { provider.openPipeHelper<Uri>(any(), any(), any(), any(), capture(worker)) } returns read
	return ImageProviderFixture(provider, loader, uri, source, read, output, worker)
}
