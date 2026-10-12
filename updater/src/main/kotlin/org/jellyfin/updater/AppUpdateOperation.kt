package org.jellyfin.updater

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.io.File

data class AppUpdateOperationState(
	val update: AppUpdate? = null,
	val downloading: Boolean = false,
	val progress: Int? = null,
	val file: File? = null,
	val message: String? = null,
	val awaitingInstallPermission: Boolean = false,
)

internal class AppUpdateOperation(
	private val fetch: suspend (AppUpdate, (Long, Long) -> Unit) -> DownloadResult,
) {
	private val current = MutableStateFlow(AppUpdateOperationState())
	val state = current.asStateFlow()
	private val mutex = Mutex()

	suspend fun download(update: AppUpdate): Boolean {
		if (!mutex.tryLock()) return false
		try {
			current.value = AppUpdateOperationState(update = update, downloading = true)
			return when (val result = fetch(update) { downloaded, total ->
				val progress = if (total > 0) (downloaded.toDouble() / total * 100).toInt().coerceIn(0, 100) else null
				if (current.value.progress != progress) current.update { it.copy(progress = progress) }
			}) {
				is DownloadResult.Ready -> {
					current.value = AppUpdateOperationState(update = update, file = result.file)
					true
				}
				is DownloadResult.Failed -> {
					current.value = AppUpdateOperationState(update = update, message = result.message)
					false
				}
			}
		} finally {
			current.update { it.copy(downloading = false, progress = null) }
			mutex.unlock()
		}
	}

	fun reset(): Boolean {
		if (!mutex.tryLock()) return false
		try {
			current.value = AppUpdateOperationState()
			return true
		} finally {
			mutex.unlock()
		}
	}

	fun install(canInstallNow: Boolean = true, start: (File) -> InstallStartResult): InstallStartResult? {
		val file = current.value.file ?: return null
		if (current.value.downloading) return null
		if (!file.isFile) {
			current.update { it.copy(file = null, awaitingInstallPermission = false, message = "Downloaded update is missing. Download it again.") }
			return null
		}
		if (!canInstallNow) {
			current.update {
				it.copy(awaitingInstallPermission = false, message = "Update downloaded. Choose Install again when you are ready.")
			}
			return null
		}
		current.update { it.copy(awaitingInstallPermission = false) }
		val result = try {
			start(file)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			InstallStartResult.Failed("Unable to open installer: ${error.message.orEmpty()}")
		}
		current.update {
			it.copy(
				awaitingInstallPermission = result == InstallStartResult.PermissionRequired,
				message = when (result) {
					InstallStartResult.Started -> "Installer opened. If you cancel, choose Install again."
					InstallStartResult.PermissionRequired -> "Allow installs from this app to continue."
					is InstallStartResult.Failed -> result.message
				},
			)
		}
		return result
	}

	fun permissionSettingsFailed(message: String) {
		current.update { it.copy(awaitingInstallPermission = false, message = message) }
	}
}
