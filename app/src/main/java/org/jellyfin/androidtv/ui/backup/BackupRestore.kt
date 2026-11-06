package org.jellyfin.androidtv.ui.backup

import org.jellyfin.androidtv.data.backup.AppBackupRestoreResult

enum class BackupFileRoute { CREATE, OPEN, GET, DOWNLOAD }
enum class DownloadsPermission { NONE, LEGACY_WRITE, ALL_FILES }

fun requiredDownloadsPermission(
	backup: Boolean,
	sdk: Int,
	legacyWriteGranted: Boolean,
	allFilesGranted: Boolean,
) = when {
	sdk < 29 && !legacyWriteGranted -> DownloadsPermission.LEGACY_WRITE
	!backup && sdk >= 30 && !allFilesGranted -> DownloadsPermission.ALL_FILES
	else -> DownloadsPermission.NONE
}

fun backupFileRoute(
	backup: Boolean,
	createHandlers: Collection<String>,
	openHandlers: Collection<String>,
	getHandlers: Collection<String>,
): BackupFileRoute {
	fun Collection<String>.usable() = any { !it.endsWith(".Stubs\$DocumentsStub") }
	return if (backup) {
		if (createHandlers.usable()) BackupFileRoute.CREATE else BackupFileRoute.DOWNLOAD
	} else when {
		openHandlers.usable() -> BackupFileRoute.OPEN
		getHandlers.usable() -> BackupFileRoute.GET
		else -> BackupFileRoute.DOWNLOAD
	}
}

fun nextBackupFileName(existing: Set<String>): String {
	var copy = 0
	while (true) {
		val name = if (copy == 0) BACKUP_FILE_NAME else "${BACKUP_FILE_STEM} ($copy).json"
		if (name !in existing) return name
		copy++
	}
}

internal suspend fun completeRestore(
	result: AppBackupRestoreResult,
	destroySession: () -> Unit,
	clearChannels: () -> Unit,
	clearLiveTvTracks: () -> Unit,
	reloadServers: suspend () -> Unit,
): Boolean {
	if (result != AppBackupRestoreResult.RESTORED) return false
	destroySession()
	clearChannels()
	clearLiveTvTracks()
	reloadServers()
	return true
}

internal const val BACKUP_FILE_STEM = "jellyfin-androidtv-backup"
internal const val BACKUP_FILE_NAME = "$BACKUP_FILE_STEM.json"
