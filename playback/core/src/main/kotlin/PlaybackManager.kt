package org.jellyfin.playback.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jellyfin.playback.core.backend.BackendService
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.backend.TrackSelectionBackend
import org.jellyfin.playback.core.mediastream.MediaStreamService
import org.jellyfin.playback.core.plugin.PlayerService
import timber.log.Timber
import kotlin.reflect.KClass
import kotlin.time.Duration

class PlaybackManager internal constructor(
	val backend: PlayerBackend,
	private val services: MutableList<PlayerService>,
	val options: PlaybackManagerOptions,
	parentJob: Job? = null,
) {
	internal val backendService = BackendService().also { service ->
		service.switchBackend(backend)
	}

	private val job = SupervisorJob(parentJob)
	val state: PlayerState = MutablePlayerState(
		options = options,
		backendService = backendService,
		queue = getService()
	)

	/**
	 * Get the track selection interface if the current backend supports it.
	 */
	val trackSelection: TrackSelectionBackend?
		get() = backendService.getTrackSelectionBackend()

	init {
		services.forEach { it.initialize(this, state, Job(job)) }
	}

	fun addService(service: PlayerService) {
		Timber.i("Adding service $service")
		service.initialize(this, state, Job(job))
		services.add(service)
	}

	fun <T : PlayerService> getService(kclass: KClass<T>): T? {
		for (service in services) {
			@Suppress("UNCHECKED_CAST")
			if (kclass.isInstance(service)) return service as T
		}
		return null
	}

	inline fun <reified T : PlayerService> getService() = getService(T::class)

	suspend fun reloadCurrentMediaStream(
		position: Duration? = null,
		playWhenReady: Boolean = true,
	): Boolean = mediaStreamService()?.reloadCurrentEntry(
		position = position,
		playWhenReady = playWhenReady,
	) == true

	fun removeService(service: PlayerService) {
		Timber.i("Removing service $service")
		service.coroutineScope.cancel()
		services.remove(service)
	}

	private fun mediaStreamService(): MediaStreamService? =
		services.firstNotNullOfOrNull { service -> service as? MediaStreamService }
}
