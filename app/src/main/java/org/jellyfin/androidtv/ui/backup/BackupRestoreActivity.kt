package org.jellyfin.androidtv.ui.backup

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.androidtv.data.backup.AppBackupManager
import org.jellyfin.androidtv.data.backup.AppBackupRestoreResult
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.ui.livetv.LiveTvTrackCache
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.applyTheme
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File
import java.io.IOException

class BackupRestoreActivity : FragmentActivity() {
	enum class Operation { BACKUP, RESTORE }

	private val backupManager by inject<AppBackupManager>()
	private val authenticationStore by inject<AuthenticationStore>()
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private lateinit var operation: Operation

	private val documentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val uri = result.data?.data
		if (result.resultCode != Activity.RESULT_OK || uri == null) {
			if (operation == Operation.RESTORE) requestDownloadsFallback() else finish()
			return@registerForActivityResult
		}
		if (operation == Operation.BACKUP) writeBackup(uri) else restoreBackup(uri)
	}
	private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) useDownloadsFallback() else finishWithToast(R.string.backup_restore_file_failed)
	}
	private val allFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
		if (granted) useDownloadsFallback()
		else finishWithToast(R.string.backup_restore_file_failed)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()
		super.onCreate(savedInstanceState)
		operation = intent.getStringExtra(EXTRA_OPERATION)?.let(Operation::valueOf) ?: return finish()
		if (savedInstanceState != null) return

		if (operation == Operation.RESTORE && authenticationStore.getServers().isNotEmpty()) {
			AlertDialog.Builder(this)
				.setTitle(R.string.backup_restore_warning_title)
				.setMessage(R.string.backup_restore_warning_body)
				.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
				.setPositiveButton(R.string.backup_restore_restore) { _, _ -> openDocument() }
				.setOnCancelListener { finish() }
				.show()
		} else openDocument()
	}

	private fun documentIntent(action: String) = Intent(action)
		.addCategory(Intent.CATEGORY_OPENABLE)
		.setType(BACKUP_MIME_TYPE)
		.apply {
			if (action == Intent.ACTION_CREATE_DOCUMENT) putExtra(Intent.EXTRA_TITLE, BACKUP_FILE_NAME)
		}

	private fun documentHandlers(action: String) = packageManager
		.queryIntentActivities(documentIntent(action), PackageManager.MATCH_DEFAULT_ONLY)
		.map { it.activityInfo.name }

	private fun openDocument() {
		val backup = operation == Operation.BACKUP
		val route = backupFileRoute(
			backup = backup,
			createHandlers = if (backup) documentHandlers(Intent.ACTION_CREATE_DOCUMENT) else emptyList(),
			openHandlers = if (backup) emptyList() else documentHandlers(Intent.ACTION_OPEN_DOCUMENT),
			getHandlers = if (backup) emptyList() else documentHandlers(Intent.ACTION_GET_CONTENT),
		)
		val action = when (route) {
			BackupFileRoute.CREATE -> Intent.ACTION_CREATE_DOCUMENT
			BackupFileRoute.OPEN -> Intent.ACTION_OPEN_DOCUMENT
			BackupFileRoute.GET -> Intent.ACTION_GET_CONTENT
			BackupFileRoute.DOWNLOAD -> return requestDownloadsFallback()
		}
		runCatching { documentLauncher.launch(documentIntent(action)) }
			.onFailure {
				Timber.w(it, "Unable to open backup document provider")
				requestDownloadsFallback()
			}
	}

	private fun requestDownloadsFallback() {
		when (requiredDownloadsPermission(
			backup = operation == Operation.BACKUP,
			sdk = Build.VERSION.SDK_INT,
			legacyWriteGranted = checkSelfPermission(
				Manifest.permission.WRITE_EXTERNAL_STORAGE,
			) == PackageManager.PERMISSION_GRANTED,
			allFilesGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
				Environment.isExternalStorageManager(),
		)) {
			DownloadsPermission.LEGACY_WRITE -> storagePermissionLauncher.launch(
				Manifest.permission.WRITE_EXTERNAL_STORAGE,
			)
			DownloadsPermission.ALL_FILES -> runCatching {
				allFilesAccessLauncher.launch(
					Intent(
						Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
						Uri.parse("package:$packageName"),
					)
				)
			}.onFailure { finishWithToast(R.string.backup_restore_file_failed) }
			DownloadsPermission.NONE -> useDownloadsFallback()
		}
	}

	private fun useDownloadsFallback() {
		if (operation == Operation.BACKUP) writeBackup(null)
		else lifecycleScope.launch {
			val uri = withContext(Dispatchers.IO) { findLatestBackup() }
			if (uri == null) finishWithToast(R.string.backup_restore_file_failed)
			else restoreBackup(uri)
		}
	}

	private fun writeBackup(destination: Uri?) {
		lifecycleScope.launch {
			val saved = withContext(Dispatchers.IO) { saveBackup(destination) }
			finishWithToast(if (saved) R.string.backup_restore_saved else R.string.backup_restore_file_failed)
		}
	}

	private fun saveBackup(destination: Uri?): Boolean {
		var createdUri: Uri? = null
		var directFile: File? = null
		return runCatching {
			val target = destination ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
				val name = nextBackupFileName(downloadBackupNames())
				contentResolver.insert(
					downloads,
					ContentValues().apply {
						put(MediaStore.MediaColumns.DISPLAY_NAME, name)
						put(MediaStore.MediaColumns.MIME_TYPE, BACKUP_MIME_TYPE)
						put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
						put(MediaStore.MediaColumns.IS_PENDING, 1)
					}
				)?.also { createdUri = it } ?: throw IOException("Could not create backup")
			} else {
				val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
				require(directory.isDirectory || directory.mkdirs()) { "Could not create Downloads directory" }
				directFile = directory.resolve(nextBackupFileName(directory.list()?.toSet().orEmpty()))
				Uri.fromFile(directFile)
			}

			openOutput(target).bufferedWriter(Charsets.UTF_8).use { writer ->
				writer.write(backupManager.createBackup())
			}
			createdUri?.let { uri ->
				require(contentResolver.update(
					uri,
					ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
					null,
					null,
				) == 1) { "Could not publish backup" }
			}
		}.onFailure { error ->
			Timber.e(error, "Unable to save app backup")
			createdUri?.let { contentResolver.delete(it, null, null) }
			directFile?.delete()
		}.isSuccess
	}

	private fun restoreBackup(source: Uri) {
		lifecycleScope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching {
					openInput(source).bufferedReader(Charsets.UTF_8).use(backupManager::restoreBackup)
				}.onFailure { Timber.e(it, "Unable to restore app backup") }
					.getOrNull()
			}
			if (result == null) return@launch finishWithToast(R.string.backup_restore_file_failed)

			val startStartup = completeRestore(
				result = result,
				destroySession = sessionRepository::destroyCurrentSession,
				clearChannels = { runCatching { LeanbackChannelWorker.clearChannels(this@BackupRestoreActivity) } },
				clearLiveTvTracks = LiveTvTrackCache::clear,
				reloadServers = serverRepository::loadStoredServers,
			)
			if (startStartup) {
				Toast.makeText(this@BackupRestoreActivity, R.string.backup_restore_restored, Toast.LENGTH_LONG).show()
				startActivity(Intent(this@BackupRestoreActivity, StartupActivity::class.java).apply {
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
				})
				finish()
			} else finishWithToast(
				if (result == AppBackupRestoreResult.ROLLBACK_FAILED) R.string.backup_restore_rollback_failed
				else R.string.backup_restore_file_failed
			)
		}
	}

	private fun downloadBackupNames(): Set<String> {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
		return contentResolver.query(
			MediaStore.Downloads.EXTERNAL_CONTENT_URI,
			arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
			"${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
			arrayOf("${Environment.DIRECTORY_DOWNLOADS}/%"),
			null,
		)?.use { cursor ->
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
		}.orEmpty()
	}

	private fun findLatestBackup(): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
		contentResolver.query(
			downloads,
			arrayOf(MediaStore.MediaColumns._ID),
			"${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
			arrayOf("$BACKUP_FILE_STEM%.json", "${Environment.DIRECTORY_DOWNLOADS}/%"),
			"${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC",
		)?.use { cursor ->
			if (cursor.moveToFirst()) ContentUris.withAppendedId(downloads, cursor.getLong(0)) else null
		}
	} else {
		Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
			.listFiles { file -> file.isFile && file.name.startsWith(BACKUP_FILE_STEM) && file.extension == "json" }
			?.maxWithOrNull(compareBy({ it.lastModified() }, { it.name.length }, File::getName))
			?.let(Uri::fromFile)
	}

	private fun openInput(uri: Uri) = if (uri.scheme == "file") {
		File(requireNotNull(uri.path)).inputStream()
	} else contentResolver.openInputStream(uri) ?: throw IOException("No backup input stream")

	private fun openOutput(uri: Uri) = if (uri.scheme == "file") {
		File(requireNotNull(uri.path)).outputStream()
	} else contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No backup output stream")

	private fun finishWithToast(message: Int) {
		Toast.makeText(this, message, Toast.LENGTH_LONG).show()
		finish()
	}

	companion object {
		private const val EXTRA_OPERATION = "operation"

		fun createIntent(context: Context, operation: Operation) = Intent(context, BackupRestoreActivity::class.java)
			.putExtra(EXTRA_OPERATION, operation.name)
	}
}

private const val BACKUP_MIME_TYPE = "application/json"
