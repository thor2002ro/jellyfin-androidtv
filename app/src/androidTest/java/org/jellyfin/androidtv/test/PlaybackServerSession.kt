package org.jellyfin.androidtv.test

import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.util.sdk.stopEncodingProcess
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreateUserByName
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.UpdateUserPassword
import org.jellyfin.sdk.model.api.UserDto
import org.koin.core.context.GlobalContext

data class ServerPlaybackFixture(
	val item: BaseItemDto,
	val source: MediaSourceInfo,
	val descriptor: PlaybackMediaDescriptor,
)

data class PlaybackWatchKey(val account: String, val descriptorId: String)

data class PlaybackServerEnvironment(
	val session: PlaybackServerSession,
	val fixtures: Map<String, ServerPlaybackFixture>,
	val selection: PlaybackCatalogSelection,
	val watchedBefore: Map<PlaybackWatchKey, PlaybackWatchState>,
) {
	suspend fun watchedDifferences(): List<String> = watchedBefore.flatMap { (key, before) ->
		val fixture = fixtures.getValue(key.descriptorId)
		val userId = if (key.account == "normal") session.normalUser.id else session.testUser.id
		diffPlaybackWatchState(before, session.watchState(userId, fixture.item)).map { difference ->
			"${key.account}/${key.descriptorId}/${difference.field}: ${difference.before} -> ${difference.after}"
		}
	}
}

class PlaybackServerSession private constructor(
	val normalApi: ApiClient,
	val testApi: ApiClient,
	val normalUser: UserDto,
	val testUser: UserDto,
) {
	companion object {
		suspend fun connect(testUsername: String): PlaybackServerSession {
			val koin = GlobalContext.get()
			val sessionRepository = koin.get<SessionRepository>()
			if (sessionRepository.currentSession.value == null) sessionRepository.restoreSession(destroyOnly = false)
			val normalApi = koin.get<ApiClient>()
			val baseUrl = requireNotNull(normalApi.baseUrl) { "The debug app is not connected to a Jellyfin server" }
			checkNotNull(normalApi.accessToken) { "The debug app has no authenticated user" }
			val normalUser = normalApi.userApi.getCurrentUser().content
			val users = normalApi.userApi.getUsers().content
			var testUser = users.firstOrNull { it.name.equals(testUsername, ignoreCase = true) }
				?: normalApi.userApi.createUserByName(CreateUserByName(testUsername, "")).content
			if (testUser.hasPassword == true || testUser.hasConfiguredPassword == true) {
				normalApi.userApi.updateUserPassword(testUser.id, UpdateUserPassword(resetPassword = true))
			}
			val currentPolicy = requireNotNull(normalApi.userApi.getUserById(testUser.id).content.policy) {
				"Test user policy is missing"
			}
			val restrictedPolicy = currentPolicy.copy(
				isAdministrator = false,
				isHidden = true,
				isDisabled = false,
				enableCollectionManagement = false,
				enableSubtitleManagement = false,
				enableLyricManagement = false,
				enableUserPreferenceAccess = false,
				enableRemoteControlOfOtherUsers = false,
				enableSharedDeviceControl = false,
				enableRemoteAccess = false,
				enableLiveTvManagement = false,
				enableLiveTvAccess = false,
				enableMediaPlayback = true,
				enableAudioPlaybackTranscoding = true,
				enableVideoPlaybackTranscoding = true,
				enablePlaybackRemuxing = true,
				enableContentDeletion = false,
				enableContentDeletionFromFolders = emptyList(),
				enableContentDownloading = false,
				enableSyncTranscoding = false,
				enableMediaConversion = false,
				enableAllFolders = true,
				enabledFolders = emptyList(),
				enableAllDevices = true,
				enabledDevices = emptyList(),
				enablePublicSharing = false,
			)
			if (restrictedPolicy != currentPolicy) normalApi.userApi.updateUserPolicy(testUser.id, restrictedPolicy)

			val jellyfin = koin.get<Jellyfin>()
			val httpOptions = koin.get<HttpClientOptions>()
			val testDevice = normalApi.deviceInfo.copy(
				id = "${normalApi.deviceInfo.id}-playback-test-${testUsername.hashCode().toUInt().toString(16)}",
				name = "${normalApi.deviceInfo.name} Playback Tests",
			)
			val loginApi = jellyfin.createApi(baseUrl = baseUrl, deviceInfo = testDevice, httpClientOptions = httpOptions)
			val authentication = loginApi.authenticationApi.authenticateUserByName(testUsername, "").content
			val token = requireNotNull(authentication.accessToken) { "Test user authentication returned no access token" }
			testUser = requireNotNull(authentication.user) { "Test user authentication returned no user" }
			val testApi = jellyfin.createApi(
				baseUrl = baseUrl,
				accessToken = token,
				deviceInfo = testDevice,
				httpClientOptions = httpOptions,
			)
			return PlaybackServerSession(normalApi, testApi, normalUser, testUser)
		}
	}

	suspend fun discoverMedia(folderName: String): List<ServerPlaybackFixture> {
		val views = testApi.userViewApi.getUserViews().content.items
		val folder = views.firstOrNull { it.name.equals(folderName, ignoreCase = true) }
			?: testApi.libraryApi.getItems(
			userId = testUser.id,
			recursive = true,
			searchTerm = folderName,
			includeItemTypes = listOf(BaseItemKind.COLLECTION_FOLDER, BaseItemKind.FOLDER, BaseItemKind.USER_VIEW),
			limit = 100,
		).content.items.firstOrNull { it.name.equals(folderName, ignoreCase = true) }
			?: error(
				"Jellyfin test folder '$folderName' was not found for user ${testUser.name}; " +
					"available views=${views.mapNotNull(BaseItemDto::name).sorted()}"
			)
		return testApi.libraryApi.getItems(
			userId = testUser.id,
			parentId = folder.id,
			recursive = true,
			fields = listOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS),
			mediaTypes = listOf(MediaType.VIDEO),
			enableUserData = false,
			limit = 10_000,
		).content.items.flatMap { item ->
			item.mediaSources.orEmpty().mapNotNull { source -> source.toFixture(item) }
		}
	}

	suspend fun loadEnvironment(folderName: String): PlaybackServerEnvironment {
		val discovered = discoverMedia(folderName)
		val fixtures = discovered.associateBy { it.descriptor.id }
		val selection = PlaybackMediaCatalog.select(discovered.map(ServerPlaybackFixture::descriptor))
		val passthroughCodecs = setOf("ac3", "eac3", "eac3_joc", "dts", "dts_hd", "dtshd", "truehd", "ac4")
		val guarded = (selection.fixtures.values + discovered.map(ServerPlaybackFixture::descriptor).filter { descriptor ->
			descriptor.audioStreams.any { it.codec?.lowercase() in passthroughCodecs }
		}).distinctBy(PlaybackMediaDescriptor::id)
		val watchedBefore = buildMap {
			for (descriptor in guarded) {
				val fixture = fixtures.getValue(descriptor.id)
				put(PlaybackWatchKey("normal", descriptor.id), watchState(normalUser.id, fixture.item))
				put(PlaybackWatchKey("test", descriptor.id), watchState(testUser.id, fixture.item))
			}
		}
		return PlaybackServerEnvironment(this, fixtures, selection, watchedBefore)
	}

	suspend fun watchState(userId: org.jellyfin.sdk.model.UUID, item: BaseItemDto): PlaybackWatchState {
		val data = normalApi.userDataApi.getItemUserData(item.id, userId).content
		return PlaybackWatchState(
			played = data.played,
			playCount = data.playCount,
			positionTicks = data.playbackPositionTicks,
			favorite = data.isFavorite,
			likes = data.likes,
			rating = data.rating,
			lastPlayed = data.lastPlayedDate?.toString(),
		)
	}

	suspend fun stopEncoding(playSessionId: String) {
		// Server 12 may retain SessionInfo.TranscodingInfo after the process exits.
		// The stop endpoint is the cleanup contract; session metadata is not a process probe.
		testApi.stopEncodingProcess(testApi.deviceInfo.id, playSessionId)
	}
}

private fun MediaSourceInfo.toFixture(item: BaseItemDto): ServerPlaybackFixture? {
	val streams = mediaStreams.orEmpty()
	val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO } ?: return null
	val audio = streams.filter { it.type == MediaStreamType.AUDIO }
	val descriptor = PlaybackMediaDescriptor(
		id = "${item.id}:${id.orEmpty()}",
		name = item.name ?: item.id.toString(),
		container = container,
		width = video.width,
		height = video.height,
		videoCodec = video.codec,
		videoProfile = video.profile,
		videoLevel = video.level?.toInt(),
		bitDepth = video.bitDepth,
		videoRange = video.videoRangeType.serialName,
		doviProfile = video.dvProfile,
		subtitleCodecs = streams.filter { it.type == MediaStreamType.SUBTITLE }.mapNotNullTo(sortedSetOf()) { it.codec },
		audioCodecs = audio.mapNotNullTo(sortedSetOf()) { it.codec },
		videoFrameRate = video.referenceFrameRate,
		videoRefFrames = video.refFrames,
		videoInterlaced = video.isInterlaced,
		videoAnamorphic = video.isAnamorphic,
		videoBitrate = video.bitRate,
		containerBitrate = bitrate ?: streams.mapNotNull { it.bitRate }.sum().takeIf { it > 0 },
		videoStreamCount = streams.count { it.type == MediaStreamType.VIDEO },
		audioStreamCount = audio.size,
		audioStreams = audio.map { stream ->
			PlaybackAudioDescriptor(
				codec = stream.codec,
				profile = stream.profile,
				channels = stream.channels,
				sampleRate = stream.sampleRate,
				bitDepth = stream.bitDepth,
				bitrate = stream.bitRate,
			)
		},
	)
	return ServerPlaybackFixture(item, this, descriptor)
}
