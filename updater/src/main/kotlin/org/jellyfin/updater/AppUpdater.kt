package org.jellyfin.updater

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

class AppUpdater(
	context: Context,
	private val config: AppUpdateConfig,
) {
	private val appContext = context.applicationContext
	private val prefs = appContext.getSharedPreferences("app_updater", Context.MODE_PRIVATE)
	private val checkMutex = Mutex()
	private val downloadMutex = Mutex()
	private var startupCacheCleared = false
	val deviceAbi = selectDeviceAbi(Build.SUPPORTED_ABIS.asList(), config.supportedAbis)
	private val selectedArtifactSuffix get() = artifactSuffixForSelection(useUniversalApk, deviceAbi, config.buildType)

	var includePrereleases: Boolean
		get() = prefs.getBoolean("include_prereleases", false)
		set(value) = prefs.edit { putBoolean("include_prereleases", value) }

	var useUniversalApk: Boolean
		get() = prefs.getBoolean(PREFERENCE_USE_UNIVERSAL_APK, true)
		set(value) = prefs.edit { putBoolean(PREFERENCE_USE_UNIVERSAL_APK, value || deviceAbi == null) }

	suspend fun initializeUseUniversalApk(): Boolean = withContext(Dispatchers.IO) {
		initializeUseUniversalApkBlocking()
		useUniversalApk
	}

	suspend fun clearUpdateCacheAtStartup() = withContext(Dispatchers.IO) {
		downloadMutex.withLock {
			if (startupCacheCleared) return@withLock
			runCatching { pruneUpdateCache(File(appContext.cacheDir, "updater")) }
			startupCacheCleared = true
		}
	}

	suspend fun checkForUpdate(force: Boolean = false): UpdateCheckResult {
		return withContext(Dispatchers.IO) {
			initializeUseUniversalApkBlocking()
			checkMutex.withLock {
				runCatching {
					if (!force && !shouldCheck()) return@runCatching UpdateCheckResult.Skipped

					val releases = parseReleases(httpGet("https://api.github.com/repos/${config.owner}/${config.repo}/releases?per_page=20"))
					prefs.edit { putLong("last_check_ms", System.currentTimeMillis()) }
					findUpdate(releases)?.let(UpdateCheckResult::Available) ?: UpdateCheckResult.NoUpdate
				}.getOrElse { error ->
					if (error is CancellationException) throw error
					UpdateCheckResult.Failed(
						"Unable to check for updates${error.message?.let { ": $it" }.orEmpty()}"
					)
				}
			}
		}
	}

	private fun initializeUseUniversalApkBlocking() = synchronized(prefs) {
		if (!prefs.contains(PREFERENCE_USE_UNIVERSAL_APK)) {
			val installedAbis = runCatching { readInstalledAbis(File(appContext.applicationInfo.sourceDir)) }.getOrDefault(emptySet())
			prefs.edit { putBoolean(PREFERENCE_USE_UNIVERSAL_APK, defaultUseUniversalApk(installedAbis, deviceAbi)) }
		}
	}

	suspend fun download(
		update: AppUpdate,
		onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
	): DownloadResult = withContext(Dispatchers.IO) {
		downloadMutex.withLock {
			val file = File(appContext.cacheDir, "updater/${update.assetName}")
			val partialFile = File(file.parentFile, "${file.name}.part")
			val expectedSha256 = githubSha256(update.assetDigest)
				?: return@withLock DownloadResult.Failed(githubDigestError(update.assetDigest))

			try {
				val directory = requireNotNull(file.parentFile)
				if (!directory.isDirectory && !directory.mkdirs()) {
					throw IOException("Unable to create update storage. Check available device storage and try again.")
				}
				pruneUpdateCache(directory, file, partialFile)

				if (
					file.isFile &&
					downloadSizeError(file.length(), update.assetSize) == null &&
					file.sha256().equals(expectedSha256, ignoreCase = true)
				) {
					return@withLock DownloadResult.Ready(file)
				}
				if (file.exists() && !file.delete()) throw IOException("Unable to replace the cached update. Please retry.")
				if (partialFile.exists() && !partialFile.delete()) throw IOException("Unable to clear the incomplete update. Please retry.")

				val actualSha256 = downloadTo(update.assetUrl, partialFile, update.assetSize, onProgress)
				if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
					return@withLock DownloadResult.Failed(
						"Downloaded update failed SHA-256 verification. The file may be incomplete or corrupted; please retry."
					)
				}
				promoteDownload(partialFile, file)
				DownloadResult.Ready(file)
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				DownloadResult.Failed(
					"Unable to download the update${error.message?.let { ": $it" }.orEmpty()}"
				)
			} finally {
				partialFile.delete()
			}
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
			InstallStartResult.Failed(
				"Unable to open Android's installer${error.message?.let { ": $it" }.orEmpty()}. Please retry."
			)
		}
	}

	fun openInstallPermissionSettings() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

		val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri())
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
		val suffix = selectedArtifactSuffix
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
				assetDigest = asset.digest,
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
							digest = obj["digest"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
						)
					}
				}.orEmpty(),
			)
		}
	}

	private fun httpGet(url: String): String {
		val connection = openConnection(url)
		return connection.useBody { code, body ->
			if (code !in 200..299) throw IOException("GitHub update check failed (HTTP $code). Please retry later.")
			body
		}
	}

	private fun downloadTo(url: String, file: File, assetSize: Long, onProgress: (Long, Long) -> Unit): String {
		val connection = openConnection(url)
		return connection.use {
			if (responseCode !in 200..299) throw IOException("GitHub update download failed (HTTP $responseCode). Please retry.")
			val total = assetSize.takeIf { it > 0 }
				?: getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
				?: 0L
			var downloaded = 0L
			val digest = MessageDigest.getInstance("SHA-256")
			inputStream.use { input ->
				FileOutputStream(file).use { output ->
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					while (true) {
						val bytes = input.read(buffer)
						if (bytes < 0) break
						output.write(buffer, 0, bytes)
						digest.update(buffer, 0, bytes)
						downloaded += bytes
						onProgress(downloaded, total)
					}
				}
			}
			downloadSizeError(downloaded, total)?.let {
				throw IOException("Update download was incomplete ($it). Please retry.")
			}
			digest.digest().toHex()
		}
	}

	private fun openConnection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
		requestMethod = "GET"
		connectTimeout = 15_000
		readTimeout = 30_000
		setRequestProperty("Accept", "application/vnd.github+json")
		setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
		setRequestProperty("User-Agent", "jellyfin-androidtv-thor")
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
		val digest: String?,
	)

	private data class UpdateCandidate(
		val update: AppUpdate,
		val version: AppVersion,
	)
}

internal fun selectDeviceAbi(deviceAbis: List<String>, artifactAbis: List<String>) =
	deviceAbis.firstOrNull(artifactAbis::contains)

internal fun readInstalledAbis(file: File) = ZipFile(file).use { apk ->
	apk.entries().asSequence()
		.map { it.name }
		.filter { it.startsWith("lib/") }
		.map { it.substringAfter("lib/").substringBefore('/') }
		.filter { it.isNotEmpty() }
		.toSet()
}

internal fun defaultUseUniversalApk(installedAbis: Set<String>, deviceAbi: String?) =
	installedAbis.size != 1 || deviceAbi == null

internal fun artifactSuffixForSelection(useUniversal: Boolean, deviceAbi: String?, buildType: String) =
	"-${if (useUniversal || deviceAbi == null) "universal" else deviceAbi}-$buildType.apk"

internal fun downloadSizeError(downloaded: Long, expected: Long): String? =
	if (expected > 0 && downloaded != expected) "Downloaded $downloaded of $expected bytes" else null

internal fun pruneUpdateCache(directory: File, vararg keep: File) {
	if (keep.isEmpty()) {
		directory.deleteRecursively()
		return
	}

	val retained = keep.toSet()
	directory.listFiles()?.forEach { file ->
		if (file.isFile && file !in retained && (file.name.endsWith(".apk") || file.name.endsWith(".apk.part"))) file.delete()
	}
}

internal fun promoteDownload(partialFile: File, file: File) {
	if (!partialFile.renameTo(file)) throw IOException("Unable to finalize the downloaded update. Check available device storage and try again.")
}

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
private fun JsonObject.boolean(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

private fun githubSha256(publishedDigest: String?): String? = publishedDigest
	?.takeIf { it.startsWith(SHA256_PREFIX, ignoreCase = true) }
	?.substring(SHA256_PREFIX.length)
	?.takeIf { it.matches(SHA256_REGEX) }

private fun githubDigestError(publishedDigest: String?) = if (publishedDigest.isNullOrBlank()) {
	"GitHub did not provide a SHA-256 digest for this update. Installation was stopped for safety."
} else {
	"GitHub provided an invalid SHA-256 digest for this update. Installation was stopped for safety."
}

private fun File.sha256(): String = inputStream().use { input ->
	val digest = MessageDigest.getInstance("SHA-256")
	val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
	while (true) {
		val bytes = input.read(buffer)
		if (bytes < 0) break
		digest.update(buffer, 0, bytes)
	}
	digest.digest().toHex()
}

private fun ByteArray.toHex() = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

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

private const val PREFERENCE_USE_UNIVERSAL_APK = "use_universal_apk"
private const val SHA256_PREFIX = "sha256:"
private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
