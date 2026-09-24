package org.jellyfin.playback.core.backend

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.playback.core.model.VideoAspectRatio
import org.jellyfin.playback.core.model.VideoGeometry
import org.jellyfin.playback.core.model.VideoOutputTransform
import org.jellyfin.playback.core.ui.PlayerSurfaceView

class BackendServiceTests : FunSpec({
	test("identical output transforms are applied once") {
		val backend = mockk<PlayerBackend>(relaxed = true)
		val service = BackendService()
		val transform = VideoOutputTransform(VideoAspectRatio(1920, 1080))
		service.switchBackend(backend)

		service.setVideoOutputTransform(transform)
		service.setVideoOutputTransform(transform)

		verify(exactly = 1) { backend.setVideoOutputTransform(transform) }
	}

	test("valid video geometry reapplies the current output transform") {
		val backend = mockk<PlayerBackend>(relaxed = true)
		val service = BackendService()
		val transform = VideoOutputTransform(VideoAspectRatio(1920, 1080))
		service.switchBackend(backend)
		service.setVideoOutputTransform(transform)

		service.BackendEventListener().onVideoGeometryChange(VideoGeometry.EMPTY)
		verify(exactly = 1) { backend.setVideoOutputTransform(transform) }

		service.BackendEventListener().onVideoGeometryChange(VideoGeometry(720, 576, 16f / 9f))
		verify(exactly = 2) { backend.setVideoOutputTransform(transform) }
	}

	test("surface attachment reapplies the current output transform") {
		val backend = mockk<PlayerBackend>(relaxed = true)
		val surface = mockk<PlayerSurfaceView>(relaxed = true)
		val service = BackendService()
		val transform = VideoOutputTransform(VideoAspectRatio(1920, 800))
		service.switchBackend(backend)
		service.setVideoOutputTransform(transform)

		service.attachSurfaceView(surface)

		verify(exactly = 1) { backend.setSurfaceView(surface) }
		verify(exactly = 2) { backend.setVideoOutputTransform(transform) }
	}

	test("backend switch clears geometry and starts the replacement neutral") {
		val initial = mockk<PlayerBackend>(relaxed = true)
		val replacement = mockk<PlayerBackend>(relaxed = true)
		val service = BackendService()
		val geometryEvents = mutableListOf<VideoGeometry>()
		service.addListener(object : PlayerBackendEventListener() {
			override fun onVideoGeometryChange(geometry: VideoGeometry) {
				geometryEvents += geometry
			}
		})
		service.switchBackend(initial)
		service.BackendEventListener().onVideoGeometryChange(VideoGeometry(720, 576, 16f / 9f))
		service.setVideoOutputTransform(VideoOutputTransform(VideoAspectRatio(1920, 1080)))

		service.switchBackend(replacement)

		geometryEvents.last() shouldBe VideoGeometry.EMPTY
		service.videoOutputTransform shouldBe VideoOutputTransform.NONE
		verify { initial.setVideoOutputTransform(VideoOutputTransform.NONE) }
		verify { replacement.setVideoOutputTransform(VideoOutputTransform.NONE) }
	}

	test("reset clears geometry and output transform before resetting backend") {
		val backend = mockk<PlayerBackend>(relaxed = true)
		val service = BackendService()
		val geometryEvents = mutableListOf<VideoGeometry>()
		service.addListener(object : PlayerBackendEventListener() {
			override fun onVideoGeometryChange(geometry: VideoGeometry) {
				geometryEvents += geometry
			}
		})
		service.switchBackend(backend)
		service.BackendEventListener().onVideoGeometryChange(VideoGeometry(1920, 800, 2.4f))
		service.setVideoOutputTransform(VideoOutputTransform(VideoAspectRatio(1920, 1080)))

		service.reset()

		geometryEvents.last() shouldBe VideoGeometry.EMPTY
		service.videoOutputTransform shouldBe VideoOutputTransform.NONE
		verify { backend.setVideoOutputTransform(VideoOutputTransform.NONE) }
		verify { backend.reset() }
	}

})
