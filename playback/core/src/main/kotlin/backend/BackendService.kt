package org.jellyfin.playback.core.backend

import androidx.core.view.doOnDetach
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.VideoGeometry
import org.jellyfin.playback.core.model.VideoOutputTransform
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView

/**
 * Service keeping track of the current playback backend and its related surface view.
 */
class BackendService {
	private var _backend: PlayerBackend? = null
	val backend get() = _backend
	val activeBackends: List<PlayerBackend> get() = listOfNotNull(_backend)

	private var listeners = mutableListOf<PlayerBackendEventListener>()
	private var _surfaceView: PlayerSurfaceView? = null
	private var _subtitleView: PlayerSubtitleView? = null
	var videoOutputTransform: VideoOutputTransform = VideoOutputTransform.NONE
		private set

	fun switchBackend(backend: PlayerBackend) {
		if (_backend === backend) return

		_backend?.setVideoOutputTransform(VideoOutputTransform.NONE)
		_backend?.reset()
		_backend?.cleanup()
		videoOutputTransform = VideoOutputTransform.NONE
		callListeners { onVideoGeometryChange(VideoGeometry.EMPTY) }

		_backend = backend.apply {
			_surfaceView?.let(::setSurfaceView)
			_subtitleView?.let(::setSubtitleView)
			setListener(BackendEventListener())
			setVideoOutputTransform(VideoOutputTransform.NONE)
			onActivated()
		}
	}

	fun attachSurfaceView(surfaceView: PlayerSurfaceView) {
		// Remove existing surface view
		if (_surfaceView != null) {
			_backend?.setSurfaceView(null)
		}

		// Apply new surface view
		_surfaceView = surfaceView.apply {
			_backend?.setSurfaceView(surfaceView)
			_backend?.setVideoOutputTransform(videoOutputTransform)

			// Automatically detach
			doOnDetach {
				if (surfaceView == _surfaceView) {
					_surfaceView = null
					_backend?.setSurfaceView(null)
				}
			}
		}
	}

	fun attachSubtitleView(subtitleView: PlayerSubtitleView) {
		// Remove existing surface view
		if (_subtitleView != null) {
			_backend?.setSubtitleView(null)
		}

		// Apply new surface view
		_subtitleView = subtitleView.apply {
			_backend?.setSubtitleView(subtitleView)

			// Automatically detach
			doOnDetach {
				if (subtitleView == _subtitleView) {
					_subtitleView = null
					_backend?.setSubtitleView(null)
				}
			}
		}
	}

	fun addListener(listener: PlayerBackendEventListener) {
		listeners.add(listener)
	}

	fun removeListener(listener: PlayerBackendEventListener) {
		listeners.remove(listener)
	}

	fun setVideoOutputTransform(transform: VideoOutputTransform) {
		if (videoOutputTransform == transform) return
		videoOutputTransform = transform
		_backend?.setVideoOutputTransform(transform)
	}

	fun clearVideoOutput() {
		videoOutputTransform = VideoOutputTransform.NONE
		_backend?.setVideoOutputTransform(VideoOutputTransform.NONE)
		callListeners { onVideoGeometryChange(VideoGeometry.EMPTY) }
	}

	fun reset() {
		clearVideoOutput()
		_backend?.reset()
	}

	fun cleanup() {
		_backend?.cleanup()
	}

	fun release() {
		clearVideoOutput()
		_backend?.release()
		_backend = null
		_surfaceView = null
		_subtitleView = null
	}

	/**
	 * Get the track selection backend if the current backend supports it.
	 */
	fun getTrackSelectionBackend(): TrackSelectionBackend? = _backend as? TrackSelectionBackend

	inner class BackendEventListener : PlayerBackendEventListener() {
		override fun onPlayStateChange(state: PlayState) {
			callListeners { onPlayStateChange(state) }
		}

		override fun onPlaybackError(error: PlaybackError) {
			callListeners { onPlaybackError(error) }
		}

		override fun onVideoGeometryChange(geometry: VideoGeometry) {
			if (geometry.videoAspectRatio > 0f) {
				_backend?.setVideoOutputTransform(videoOutputTransform)
			}
			callListeners { onVideoGeometryChange(geometry) }
		}

		override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) {
			callListeners { onMediaStreamEnd(mediaStream) }
		}

		override fun onTracksChanged() {
			callListeners { onTracksChanged() }
		}

		override fun onSubtitleTimingOffsetSupportChange(supported: Boolean, resetTimingOnUnsupported: Boolean) {
			callListeners { onSubtitleTimingOffsetSupportChange(supported, resetTimingOnUnsupported) }
		}
	}

	private fun <T> callListeners(
		body: PlayerBackendEventListener.() -> T
	): List<T> = listeners.map { listener -> listener.body() }
}
