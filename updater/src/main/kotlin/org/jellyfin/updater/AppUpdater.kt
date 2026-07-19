package org.jellyfin.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(
	context: Context,
	private val config: AppUpdateConfig,
) {
	private val appContext = context.applicationContext
	private val prefs = appContext.getSharedPreferences("app_updater", Context.MODE_PRIVATE)

	var includePrereleases: Boolean
		get() = prefs.getBoolean("include_prereleases", false)
		set(value) = prefs.edit().putBoolean("include_prereleases", value).apply()

	suspend fun checkForUpdate(force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
		if (!force && !shouldCheck()) return@withContext UpdateCheckResult.Skipped

		runCatching {
			val releases = parseReleases(httpGet("https://api.github.com/repos/${config.owner}/${config.repo}/releases?per_page=20"))
			prefs.edit().putLong("last_check_ms", System.currentTimeMillis()).apply()
			findUpdate(releases)?.let(UpdateCheckResult::Available) ?: UpdateCheckResult.NoUpdate
		}.getOrElse { error ->
			UpdateCheckResult.Failed(error.message ?: "Update check failed")
		}
	}

	suspend fun download(
		update: AppUpdate,
		onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
	): DownloadResult = withContext(Dispatchers.IO) {
		runCatching {
			val file = File(appContext.cacheDir, "updater/${update.assetName}")
			file.parentFile?.mkdirs()
			downloadTo(update.assetUrl, file, update.assetSize, onProgress)
			validateApk(file)?.let { error ->
				file.delete()
				return@withContext DownloadResult.Failed(error)
			}
			DownloadResult.Ready(file)
		}.getOrElse { error ->
			DownloadResult.Failed(error.message ?: "Download failed")
		}
	}

	@Suppress("DEPRECATION")
	fun startInstall(file: File): InstallStartResult {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
			return InstallStartResult.PermissionRequired
		}

		return runCatching {
			val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updater.fileprovider", file)
			val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
				data = uri
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
				putExtra(Intent.EXTRA_RETURN_RESULT, true)
			}
			appContext.startActivity(intent)
			InstallStartResult.Started
		}.getOrElse { error ->
			InstallStartResult.Failed(error.message ?: "Unable to start installer")
		}
	}

	fun openInstallPermissionSettings() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

		val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		appContext.startActivity(intent)
	}

	private fun shouldCheck(): Boolean {
		val lastCheck = prefs.getLong("last_check_ms", 0L)
		return System.currentTimeMillis() - lastCheck >= config.checkIntervalMillis
	}

	private fun findUpdate(releases: List<GitHubRelease>): AppUpdate? {
		val currentVersion = AppVersion.parse(config.currentVersionName) ?: return null

		return releases.asSequence()
			.filter { release -> !release.draft && (includePrereleases || !release.prerelease) }
			.flatMap { release -> release.assets.asSequence().mapNotNull { asset -> release.toUpdate(asset) } }
			.filter { (_, version) -> version > currentVersion }
			.maxByOrNull { (_, version) -> version }
			?.update
	}

	private fun GitHubRelease.toUpdate(asset: GitHubAsset): UpdateCandidate? {
		val suffix = "-${config.buildType}.apk"
		if (!asset.name.startsWith(config.artifactPrefix) || !asset.name.endsWith(suffix)) return null

		val versionName = asset.name
			.removePrefix(config.artifactPrefix)
			.removeSuffix(suffix)
		val version = AppVersion.parse(versionName) ?: return null

		return UpdateCandidate(
			update = AppUpdate(
				versionName = versionName,
				buildType = config.buildType,
				releaseName = name.ifBlank { tagName },
				tagName = tagName,
				releaseUrl = htmlUrl,
				releaseNotes = body,
				prerelease = prerelease,
				publishedAt = publishedAt,
				assetName = asset.name,
				assetUrl = asset.downloadUrl,
				assetSize = asset.size,
			),
			version = version,
		)
	}

	private fun parseReleases(text: String): List<GitHubRelease> {
		return Json.parseToJsonElement(text).jsonArray.map { element ->
			val release = element.jsonObject
			GitHubRelease(
				tagName = release.string("tag_name"),
				name = release.string("name"),
				htmlUrl = release.string("html_url"),
				body = release.string("body"),
				draft = release.boolean("draft"),
				prerelease = release.boolean("prerelease"),
				publishedAt = release.string("published_at"),
				assets = release["assets"]?.jsonArrayOrNull()?.map { asset ->
					asset.jsonObject.let { obj ->
						GitHubAsset(
							name = obj.string("name"),
							downloadUrl = obj.string("browser_download_url"),
							size = obj.long("size"),
						)
					}
				}.orEmpty(),
			)
		}
	}

	private fun httpGet(url: String): String {
		val connection = openConnection(url)
		return connection.useBody { code, body ->
			if (code !in 200..299) throw IOException("Request failed: $code")
			body
		}
	}

	private fun downloadTo(url: String, file: File, assetSize: Long, onProgress: (Long, Long) -> Unit) {
		val connection = openConnection(url)
		connection.use {
			if (responseCode !in 200..299) throw IOException("Download failed: $responseCode")
			val total = getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: assetSize
			var downloaded = 0L
			inputStream.use { input ->
				FileOutputStream(file).use { output ->
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					while (true) {
						val bytes = input.read(buffer)
						if (bytes < 0) break
						output.write(buffer, 0, bytes)
						downloaded += bytes
						onProgress(downloaded, total)
					}
				}
			}
		}
	}

	private fun openConnection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
		requestMethod = "GET"
		connectTimeout = 15_000
		readTimeout = 30_000
		setRequestProperty("Accept", "application/vnd.github+json")
		setRequestProperty("User-Agent", "jellyfin-androidtv-thor")
	}

	private fun validateApk(file: File): String? {
		val packageManager = appContext.packageManager
		val archiveInfo = packageManager.getArchivePackageInfo(file) ?: return "Downloaded APK could not be read"
		val installedInfo = packageManager.getInstalledPackageInfo(appContext.packageName)

		if (archiveInfo.packageName != appContext.packageName) {
			return "Downloaded APK is for ${archiveInfo.packageName}, not ${appContext.packageName}"
		}

		if (archiveInfo.longVersionCodeCompat() <= installedInfo.longVersionCodeCompat()) {
			return "Downloaded APK is not newer than the installed app"
		}

		return null
	}

	private data class GitHubRelease(
		val tagName: String,
		val name: String,
		val htmlUrl: String,
		val body: String,
		val draft: Boolean,
		val prerelease: Boolean,
		val publishedAt: String,
		val assets: List<GitHubAsset>,
	)

	private data class GitHubAsset(
		val name: String,
		val downloadUrl: String,
		val size: Long,
	)

	private data class UpdateCandidate(
		val update: AppUpdate,
		val version: AppVersion,
	)
}

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
private fun JsonObject.boolean(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

private fun HttpURLConnection.useBody(block: (Int, String) -> String): String = use {
	val stream = if (responseCode in 200..299) inputStream else errorStream
	val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
	block(responseCode, body)
}

private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
	try {
		return block()
	} finally {
		disconnect()
	}
}

@Suppress("DEPRECATION")
private fun PackageManager.getArchivePackageInfo(file: File): PackageInfo? =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
		getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
	} else {
		getPackageArchiveInfo(file.absolutePath, 0)
	}

@Suppress("DEPRECATION")
private fun PackageManager.getInstalledPackageInfo(packageName: String): PackageInfo =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
		getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
	} else {
		getPackageInfo(packageName, 0)
	}

@Suppress("DEPRECATION")
private fun PackageInfo.longVersionCodeCompat(): Long =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
