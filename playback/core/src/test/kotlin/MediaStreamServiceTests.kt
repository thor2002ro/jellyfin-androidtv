package org.jellyfin.playback.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.MediaStreamService
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.supplier.QueueSupplier
import org.jellyfin.playback.jellyfin.JellyfinDeviceProfileRequest
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamResolver
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.playback.jellyfin.queue.liveStreamId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.VideoApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile
import org.jellyfin.sdk.model.api.MediaType
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MediaStreamServiceTests : FunSpec({
	test("a late Jellyfin response cannot overwrite the accepted source and live stream identifiers") {
		val api = mockk<ApiClient>()
		every { api.getOrCreateApi(MediaInfoApi::class, any()) } returns MediaInfoApi(api)
		every { api.getOrCreateApi(VideoApi::class, any()) } returns VideoApi(api)
		every { api.createUrl(any(), any(), any(), any()) } returns "https://server.test/video.ts"
		val olderStarted = CompletableDeferred<Unit>()
		val newerStarted = CompletableDeferred<Unit>()
		val olderResponse = CompletableDeferred<RawResponse>()
		val newerResponse = CompletableDeferred<RawResponse>()
		val requests = AtomicInteger()
		coEvery { api.request(any(), any(), any(), any(), any()) } coAnswers {
			secondArg<String>() shouldBe "/Items/{itemId}/PlaybackInfo"
			if (requests.incrementAndGet() == 1) {
				olderStarted.complete(Unit)
				olderResponse.await()
			} else {
				newerStarted.complete(Unit)
				newerResponse.await()
			}
		}
		val resolver = JellyfinMediaStreamResolver(api, { JellyfinDeviceProfileRequest(buildDeviceProfile {}, 0L) })
		val fixture = ReloadFixture(resolver)
		fixture.first.baseItem = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL, mediaType = MediaType.VIDEO)
		fixture.first.mediaSourceId = "original-source"
		fixture.first.liveStreamId = "original-live"
		fixture.selectFirst()

		coroutineScope {
			val older = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.reloadCurrentMediaStream() }
			olderStarted.await()
			val newer = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.reloadCurrentMediaStream() }
			newerStarted.await()
			newerResponse.complete(playbackInfoResponse("newer"))
			newer.await() shouldBe true
			olderResponse.complete(playbackInfoResponse("older"))
			older.await() shouldBe false
		}

		fixture.first.mediaStream?.identifier shouldBe "newer-session"
		fixture.first.mediaSourceId shouldBe "newer-source"
		fixture.first.liveStreamId shouldBe "newer-live"
		verify(exactly = 1) { fixture.backend.replaceItem(fixture.first) }

		// Initial stream caching must publish the same metadata before playback/reporting can observe it.
		val initialEntry = QueueEntry().apply {
			baseItem = fixture.first.baseItem
			mediaSourceId = "initial-source"
			liveStreamId = "initial-live"
		}
		val resolved = requireNotNull(resolver.getStream(initialEntry, null))
		initialEntry.mediaSourceId shouldBe "initial-source"
		initialEntry.liveStreamId shouldBe "initial-live"
		initialEntry.mediaStream = resolved
		initialEntry.mediaSourceId shouldBe "newer-source"
		initialEntry.liveStreamId shouldBe "newer-live"
	}
	test("a delayed reload cannot replace a different queue entry or clear its stream") {
		val fixture = ReloadFixture()
		fixture.selectFirst()
		coroutineScope {
			val reload = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.reloadCurrentMediaStream(30.seconds) }
			fixture.resolver.started.await()
			fixture.queue.setIndex(1)
			fixture.resolver.response.complete(fixture.reloadedStream)

			reload.await() shouldBe false
		}
		fixture.queue.entry.value shouldBeSameInstanceAs fixture.second
		fixture.second.mediaStream shouldBeSameInstanceAs fixture.secondStream
		fixture.first.mediaStream shouldBeSameInstanceAs fixture.firstStream
		verify(exactly = 0) { fixture.backend.replaceItem(any()) }
	}

	test("stopping while resolution is pending prevents playback from restarting") {
		val fixture = ReloadFixture()
		fixture.selectFirst()
		coroutineScope {
			val reload = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.reloadCurrentMediaStream(30.seconds) }
			fixture.resolver.started.await()
			fixture.manager.state.stop()
			fixture.resolver.response.complete(fixture.reloadedStream)

			reload.await() shouldBe false
		}
		fixture.queue.entry.value shouldBe null
		fixture.first.mediaStream shouldBeSameInstanceAs fixture.firstStream
		verify(exactly = 0) { fixture.backend.replaceItem(any()) }
	}

	test("stopping and selecting the same entry again invalidates its pending reload") {
		val fixture = ReloadFixture()
		fixture.selectFirst()
		coroutineScope {
			val reload = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.reloadCurrentMediaStream(30.seconds) }
			fixture.resolver.started.await()
			fixture.manager.state.stop()
			fixture.selectFirst()
			fixture.resolver.response.complete(fixture.reloadedStream)

			reload.await() shouldBe false
		}
		fixture.queue.entry.value shouldBeSameInstanceAs fixture.first
		fixture.first.mediaStream shouldBeSameInstanceAs fixture.firstStream
		verify(exactly = 0) { fixture.backend.replaceItem(any()) }
	}

	test("a profile resolved for an old backend cannot replace an item on its replacement") {
		val fixture = ReloadFixture()
		fixture.selectFirst()
		val replacement = mockk<PlayerBackend>(relaxed = true)
		coroutineScope {
			val reload = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.reloadCurrentMediaStream(30.seconds) }
			fixture.resolver.started.await()
			fixture.manager.switchBackend(replacement)
			fixture.resolver.response.complete(fixture.reloadedStream)

			reload.await() shouldBe false
		}
		fixture.first.mediaStream shouldBeSameInstanceAs fixture.firstStream
		verify(exactly = 0) { replacement.replaceItem(any()) }
	}

	test("a current reload replaces playback and preserves the requested paused state") {
		val fixture = ReloadFixture()
		fixture.selectFirst()
		fixture.resolver.response.complete(fixture.reloadedStream)

		fixture.manager.reloadCurrentMediaStream(30.seconds, playWhenReady = false) shouldBe true

		fixture.resolver.startPosition shouldBe 30.seconds
		fixture.first.mediaStream shouldBeSameInstanceAs fixture.reloadedStream
		fixture.second.mediaStream shouldBe null
		verify(exactly = 1) { fixture.backend.replaceItem(fixture.first) }
		verify(exactly = 1) { fixture.backend.pause() }
	}

	test("a newer reload supersedes an older response that arrives first") {
		val fixture = ReloadFixture()
		fixture.selectFirst()
		val newestStream = stream(fixture.first, "newest")
		coroutineScope {
			val older = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.reloadCurrentMediaStream(30.seconds) }
			fixture.resolver.started.await()
			val newer = async(start = CoroutineStart.UNDISPATCHED) { fixture.manager.reloadCurrentMediaStream(60.seconds) }
			fixture.resolver.secondStarted.await()
			fixture.resolver.response.complete(fixture.reloadedStream)
			val olderResult = older.await()
			fixture.resolver.secondResponse.complete(newestStream)

			olderResult shouldBe false
			newer.await() shouldBe true
		}
		fixture.first.mediaStream shouldBeSameInstanceAs newestStream
		verify(exactly = 1) { fixture.backend.replaceItem(fixture.first) }
	}
})

private class ReloadFixture(resolverOverride: MediaStreamResolver? = null) {
	val first = QueueEntry()
	val second = QueueEntry()
	val firstStream = stream(first, "first")
	val secondStream = stream(second, "second")
	val reloadedStream = stream(first, "reloaded")
	val backend = mockk<PlayerBackend>(relaxed = true)
	val resolver = DelayedResolver()
	val queue = QueueService()
	val manager = PlaybackManager(
		backend = backend,
		services = mutableListOf(queue, MediaStreamService(listOf(resolverOverride ?: resolver), Duration.ZERO)),
		options = PlaybackManagerOptions(NoOpPlayerVolumeState(), { 10.seconds }, { 10.seconds }),
		// These tests drive queue selection and reload directly, without background playback services.
		parentJob = Job().apply { cancel() },
	)

	init {
		first.mediaStream = firstStream
		second.mediaStream = secondStream
	}

	suspend fun selectFirst() {
		queue.addSupplier(object : QueueSupplier {
			override val size = 2
			override suspend fun getItem(index: Int) = listOf(first, second).getOrNull(index)
		})
		queue.setIndex(0)
		queue.peekNext()
	}
}

private class DelayedResolver : MediaStreamResolver {
	val started = CompletableDeferred<Unit>()
	val response = CompletableDeferred<PlayableMediaStream>()
	val secondStarted = CompletableDeferred<Unit>()
	val secondResponse = CompletableDeferred<PlayableMediaStream>()
	private val requests = AtomicInteger()
	var startPosition: Duration? = null

	override suspend fun getStream(queueEntry: QueueEntry, startPosition: Duration?): PlayableMediaStream {
		this.startPosition = startPosition
		if (requests.incrementAndGet() == 2) {
			secondStarted.complete(Unit)
			return secondResponse.await()
		}
		started.complete(Unit)
		return response.await()
	}
}

private fun stream(entry: QueueEntry, identifier: String) = PlayableMediaStream(
	identifier = identifier,
	conversionMethod = MediaConversionMethod.None,
	container = MediaStreamContainer("mkv"),
	tracks = emptyList(),
	queueEntry = entry,
	url = "https://example.test/$identifier.mkv",
)

private fun playbackInfoResponse(prefix: String) = RawResponse(
	body = """
		{"PlaySessionId":"$prefix-session","MediaSources":[{
			"Id":"$prefix-source","LiveStreamId":"$prefix-live","Protocol":"File","Type":"Default",
			"Container":"ts","MediaStreams":[],"IsRemote":false,"ReadAtNativeFramerate":false,
			"IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,"SupportsTranscoding":true,
			"SupportsDirectStream":true,"SupportsDirectPlay":true,"IsInfiniteStream":true,
			"RequiresOpening":false,"RequiresClosing":true,"RequiresLooping":false,"SupportsProbing":true,
			"TranscodingSubProtocol":"hls","HasSegments":false
		}]}
	""".trimIndent().encodeToByteArray(),
	status = 200,
	headers = emptyMap(),
)
