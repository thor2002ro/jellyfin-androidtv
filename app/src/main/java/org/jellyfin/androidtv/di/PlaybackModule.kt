package org.jellyfin.androidtv.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.ExoPlayerBackendSettings
import org.jellyfin.androidtv.preference.LibVlcBackendSettings
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.libVlcAudioOutput
import org.jellyfin.androidtv.preference.libVlcDecoder
import org.jellyfin.androidtv.preference.playbackBackend
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpeg
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegAudioForLiveTv
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegVideo
import org.jellyfin.androidtv.preference.preferExoPlayerFfmpegVideoForLiveTv
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.preference.constant.libVlcPlaybackOptions
import org.jellyfin.androidtv.preference.constant.libVlcStartupOptions
import org.jellyfin.androidtv.preference.constant.toPlaybackBufferOptions
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.TrackSelectionResolver
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.playback.core.playbackManager
import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.playback.jellyfin.jellyfinPlugin
import org.jellyfin.playback.jellyfin.mediastream.JellyfinMediaStreamOptions
import org.jellyfin.playback.libvlc.LibVlcBackend
import org.jellyfin.playback.libvlc.LibVlcInstanceOptions
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend
import org.jellyfin.playback.media3.exoplayer.ExoPlayerOptions
import org.jellyfin.playback.media3.session.MediaSessionOptions
import org.jellyfin.playback.media3.session.media3SessionPlugin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.jellyfin.androidtv.ui.playback.PlaybackManager as LegacyPlaybackManager

val playbackModule = module {
	single { LegacyPlaybackManager(get()) }
	single { VideoQueueManager(get()) }
	single<MediaManager> { RewriteMediaManager(get(), get()) }

	single { PlaybackLauncher(get(), get(), get(), get()) }

	single<HttpDataSource.Factory> {
		val okHttpFactory = get<OkHttpFactory>()
		val httpClientOptions = get<HttpClientOptions>().copy(
			// Disable request timeout for media playback as this causes issues with Live TV
			requestTimeout = Duration.ZERO
		)

		OkHttpDataSource.Factory(okHttpFactory.createClient(httpClientOptions))
	}

	single { createExoPlayerBackend() }
	single { createLibVlcBackend() }
	single { createPlaybackManager() }
	single { ExoPlayerBackendSettings(get(), get()) }
	single { LibVlcBackendSettings(get(), get(), get()) }
}

private fun Scope.createExoPlayerBackend(): ExoPlayerBackend {
	val userPreferences = get<UserPreferences>()
	val exoPlayerOptions = ExoPlayerOptions(
		preferFfmpegAudio = { userPreferences[UserPreferences.preferExoPlayerFfmpeg] },
		preferFfmpegAudioForLiveTv = { userPreferences[UserPreferences.preferExoPlayerFfmpegAudioForLiveTv] },
		preferFfmpegVideo = { userPreferences[UserPreferences.preferExoPlayerFfmpegVideo] },
		preferFfmpegVideoForLiveTv = { userPreferences[UserPreferences.preferExoPlayerFfmpegVideoForLiveTv] },
		enableLibass = userPreferences[UserPreferences.assDirectPlay],
		libassRenderType = userPreferences[UserPreferences.libassRenderType].assRenderType,
		libassGlyphSize = userPreferences[UserPreferences.libassGlyphSize].glyphs,
		libassCacheSize = userPreferences[UserPreferences.libassCacheSize].megabytes,
		libassMaxRenderPixels = userPreferences[UserPreferences.libassMaxRenderPixels].pixels,
		parseSubtitlesDuringExtraction = userPreferences[UserPreferences.exoPlayerParseSubtitlesDuringExtraction],
		enableDebugLogging = userPreferences[UserPreferences.debuggingEnabled],
		baseDataSourceFactory = get<HttpDataSource.Factory>(),
	)
	return ExoPlayerBackend(androidContext(), exoPlayerOptions)
}

private fun Scope.createLibVlcBackend(): LibVlcBackend {
	val userPreferences = get<UserPreferences>()
	return LibVlcBackend(
		context = androidContext(),
		instanceOptionsProvider = {
			LibVlcInstanceOptions(
				arguments = userPreferences.libVlcStartupOptions(),
				audioOutput = userPreferences[UserPreferences.libVlcAudioOutput].vlcValue,
			)
		},
		videoDecoderProvider = { userPreferences[UserPreferences.libVlcDecoder].decoder },
		playbackOptionsProvider = { userPreferences.libVlcPlaybackOptions() },
	)
}

fun Scope.createPlaybackManager() = playbackManager(androidContext()) {
	val activityIntent = Intent(get(), MainActivity::class.java)
	val pendingIntent = PendingIntent.getActivity(get(), 0, activityIntent, PendingIntent.FLAG_IMMUTABLE)

	val notificationChannelId = "session"
	if (AndroidVersion.isAtLeastO) {
		val channel = NotificationChannel(
			notificationChannelId,
			notificationChannelId,
			NotificationManager.IMPORTANCE_LOW
		)
		channel.setShowBadge(false)
		NotificationManagerCompat.from(get()).createNotificationChannel(channel)
	}

	val userPreferences = get<UserPreferences>()
	val backend = when (userPreferences[UserPreferences.playbackBackend]) {
		PlaybackBackend.EXOPLAYER -> get<ExoPlayerBackend>()
		PlaybackBackend.LIBVLC -> get<LibVlcBackend>()
	}
	install(playbackPlugin { provide(backend) })

	val mediaSessionOptions = MediaSessionOptions(
		channelId = notificationChannelId,
		notificationId = 1,
		iconSmall = R.drawable.app_icon_foreground,
		openIntent = pendingIntent,
	)
	install(media3SessionPlugin(get(), mediaSessionOptions))

	val deviceProfileBuilder = { createDeviceProfile(androidContext(), userPreferences, get()) }
	val videoQueueManager = get<VideoQueueManager>()
	install(jellyfinPlugin(
		api = get(),
		deviceProfileBuilder = deviceProfileBuilder,
		mediaStreamOptionsProvider = { item, mediaSourceId ->
			createJellyfinMediaStreamOptions(
				item = item,
				mediaSourceId = mediaSourceId,
				videoQueueManager = videoQueueManager,
				userPreferences = userPreferences,
			)
		},
		lifecycle = ProcessLifecycleOwner.get().lifecycle,
		liveTvDirectPlayEnabled = { userPreferences[UserPreferences.liveTvDirectPlayEnabled] },
		networkAvailable = { androidContext().isNetworkAvailable() },
	))

	// Options
	val userSettingPreferences = get<UserSettingPreferences>()
	defaultRewindAmount = { userSettingPreferences[UserSettingPreferences.skipBackLength].milliseconds }
	defaultFastForwardAmount = { userSettingPreferences[UserSettingPreferences.skipForwardLength].milliseconds }
	bufferOptions = { userPreferences[UserPreferences.bufferLength].toPlaybackBufferOptions() }
}

private fun createJellyfinMediaStreamOptions(
	item: BaseItemDto,
	mediaSourceId: String?,
	videoQueueManager: VideoQueueManager,
	userPreferences: UserPreferences,
): JellyfinMediaStreamOptions {
	val mediaSource = item.mediaSources
		?.firstOrNull { mediaSource -> mediaSourceId == null || mediaSource.id == mediaSourceId }
		?: item.mediaSources?.firstOrNull()

	return JellyfinMediaStreamOptions(
		audioStreamIndex = TrackSelectionResolver.resolvePlaybackAudioStreamIndex(item, mediaSource, videoQueueManager),
		subtitleStreamIndex = TrackSelectionResolver.resolvePlaybackSubtitleStreamIndex(item, mediaSource, videoQueueManager),
		alwaysBurnInSubtitleWhenTranscoding = userPreferences[UserPreferences.subtitlesBurnDuringTranscode],
	)
}

@Suppress("DEPRECATION")
private fun Context.isNetworkAvailable(): Boolean {
	val connectivityManager = getSystemService<ConnectivityManager>() ?: return true
	fun fallbackConnected() = connectivityManager.activeNetworkInfo?.isConnectedOrConnecting == true

	if (AndroidVersion.sdkInt < 23) {
		return fallbackConnected()
	}

	val network = connectivityManager.activeNetwork ?: return fallbackConnected()
	val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return fallbackConnected()
	return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
		capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
		fallbackConnected()
}
