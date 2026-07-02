package org.jellyfin.updater

import java.io.File

data class AppUpdateConfig(
	val owner: String,
	val repo: String,
	val artifactPrefix: String,
	val currentVersionName: String,
	val buildType: String,
	val checkIntervalMillis: Long = 5L * 24 * 60 * 60 * 1000,
)

data class AppUpdate(
	val versionName: String,
	val buildType: String,
	val releaseName: String,
	val tagName: String,
	val releaseUrl: String,
	val releaseNotes: String,
	val prerelease: Boolean,
	val publishedAt: String,
	val assetName: String,
	val assetUrl: String,
	val assetSize: Long,
)

sealed interface UpdateCheckResult {
	data class Available(val update: AppUpdate) : UpdateCheckResult
	data object NoUpdate : UpdateCheckResult
	data object Skipped : UpdateCheckResult
	data class Failed(val message: String) : UpdateCheckResult
}

sealed interface DownloadResult {
	data class Ready(val file: File) : DownloadResult
	data class Failed(val message: String) : DownloadResult
}

sealed interface InstallStartResult {
	data object Started : InstallStartResult
	data object PermissionRequired : InstallStartResult
	data class Failed(val message: String) : InstallStartResult
}
