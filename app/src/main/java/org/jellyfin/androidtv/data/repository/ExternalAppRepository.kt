package org.jellyfin.androidtv.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.core.net.toUri
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.playbackPlayerPreferences
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.ui.playback.external.ExternalPlayerApi
import org.jellyfin.androidtv.util.componentName

class ExternalAppRepository(
	private val userPreferences: UserPreferences,
	private val externalPlayerApis: List<ExternalPlayerApi>,
	val defaultExternalPlayerApi: ExternalPlayerApi,
) {
	companion object {
		const val SAMPLE_VIDEO_URL = "http://jellyfin.local/query.mp4"
		const val MEDIA_TYPE_VIDEO = "video/*"
	}

	private val externalPlayerAppIntent = Intent(Intent.ACTION_VIEW).apply {
		setDataAndTypeAndNormalize(SAMPLE_VIDEO_URL.toUri(), MEDIA_TYPE_VIDEO)
	}

	fun getExternalPlayerApps(context: Context): List<ResolveInfo> = context.packageManager
		.queryIntentActivities(externalPlayerAppIntent, 0)
		// Hide apps with priority below zero (system stubs)
		.filter { it.priority >= 0 }

	fun getCurrentExternalPlayerApp(
		context: Context,
		hdr: Boolean = false,
		externalApps: List<ResolveInfo>? = null,
	): ActivityInfo? {
		val effectiveHdr = hdr && userPreferences[UserPreferences.playbackPlayerPreferences(hdr).playbackBackend] != PlaybackBackend.SAME_VIDEO_PLAYER
		val playerPreferences = UserPreferences.playbackPlayerPreferences(effectiveHdr)

		// Validate if external app should be used at all
		val useExternalPlayer = userPreferences[playerPreferences.useExternalPlayer]
		if (!useExternalPlayer) return null

		// Resolve external app information
		val apps = externalApps ?: getExternalPlayerApps(context)
		val configuredComponent = userPreferences[playerPreferences.externalPlayerComponentName]
			.takeIf { it.isNotEmpty() }
			?.let(ComponentName::unflattenFromString)
		val resolvedInfo = apps
			.firstOrNull { it.activityInfo.componentName == configuredComponent }
			?.activityInfo
		if (resolvedInfo != null) return resolvedInfo

		// Fallback in case the app is uninstalled or unavailable for some other reason
		val fallback = apps
			.let { compatibleApps -> compatibleApps.find { it.isDefault } ?: compatibleApps.firstOrNull() }
			?.activityInfo

		setExternalPlayerapp(fallback, effectiveHdr)
		return fallback
	}

	fun setExternalPlayerapp(activityInfo: ActivityInfo?, hdr: Boolean = false) {
		val playerPreferences = UserPreferences.playbackPlayerPreferences(hdr)
		if (activityInfo == null) {
			userPreferences[playerPreferences.useExternalPlayer] = false
			userPreferences[playerPreferences.externalPlayerComponentName] = ""
		} else {
			userPreferences[playerPreferences.useExternalPlayer] = true
			userPreferences[playerPreferences.externalPlayerComponentName] = activityInfo.componentName.flattenToShortString()
		}
	}

	fun getExternalPlayerApi(activityInfo: ActivityInfo) = externalPlayerApis.firstOrNull { player ->
		player.supports(activityInfo.applicationInfo)
	} ?: defaultExternalPlayerApi
}
