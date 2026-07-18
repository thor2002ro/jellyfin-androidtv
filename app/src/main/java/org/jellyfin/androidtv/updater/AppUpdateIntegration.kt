package org.jellyfin.androidtv.updater

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ProgressButton
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.design.Tokens
import org.jellyfin.updater.AppUpdate
import org.jellyfin.updater.AppUpdater
import org.jellyfin.updater.DownloadResult
import org.jellyfin.updater.InstallStartResult
import org.jellyfin.updater.UpdateCheckResult
import org.koin.compose.koinInject

fun startAppUpdateCheck(
	appUpdater: AppUpdater,
	notificationsRepository: NotificationsRepository,
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		when (val result = appUpdater.checkForUpdate(force = false)) {
			is UpdateCheckResult.Available -> notificationsRepository.updateAppUpdateNotification(result.update)
			else -> Unit
		}
	}
}

@Composable
fun AppUpdatePrompt(
	notificationsRepository: NotificationsRepository = koinInject(),
	appUpdater: AppUpdater = koinInject(),
) {
	val update by notificationsRepository.appUpdatePrompt.collectAsState()
	val availableUpdate = update ?: return
	val lifecycleScope = LocalLifecycleOwner.current.lifecycleScope
	var downloading by remember(availableUpdate) { mutableStateOf(false) }
	var downloadProgress by remember(availableUpdate) { mutableStateOf<Int?>(null) }
	var updateMessage by remember(availableUpdate) { mutableStateOf<String?>(null) }

	SettingsDialog(
		visible = true,
		onDismissRequest = { notificationsRepository.dismissAppUpdatePrompt() },
	) {
		UpdatePromptContent(
			update = availableUpdate,
			downloading = downloading,
			downloadProgress = downloadProgress,
			message = updateMessage,
			onUpdate = {
				downloading = true
				downloadProgress = 0
				updateMessage = null
				lifecycleScope.launch {
					val install = appUpdater.downloadAndStartInstall(
						availableUpdate,
						lifecycleScope.downloadProgressUpdater(
							isDownloading = { downloading },
							setProgress = { downloadProgress = it },
						),
					)
					downloading = false
					downloadProgress = null
					if (install.installerStarted) {
						notificationsRepository.dismissAppUpdatePrompt()
					} else {
						updateMessage = install.message
					}
				}
			},
			onClose = { notificationsRepository.dismissAppUpdatePrompt() },
		)
	}
}

@Composable
fun AppUpdateSettingsScreen() {
	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text("SETTINGS") },
				headingContent = { Text("App updates") },
			)
		}

		item {
			AppUpdateSettings()
		}
	}
}

@Composable
fun AppUpdateSettings(
	modifier: Modifier = Modifier,
	appUpdater: AppUpdater = koinInject(),
	notificationsRepository: NotificationsRepository = koinInject(),
) {
	val context = LocalContext.current
	val lifecycleScope = LocalLifecycleOwner.current.lifecycleScope
	val availableAppUpdate by notificationsRepository.appUpdate.collectAsState()
	var includePrereleases by remember { mutableStateOf(appUpdater.includePrereleases) }
	var checking by remember { mutableStateOf(false) }
	var downloading by remember { mutableStateOf(false) }
	var downloadProgress by remember { mutableStateOf<Int?>(null) }
	var update by remember { mutableStateOf<AppUpdate?>(null) }
	var updateMessage by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(availableAppUpdate) {
		availableAppUpdate?.let {
			update = it
			updateMessage = "Update ${it.versionName} is available"
		}
	}

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs),
	) {
		ListButton(
			leadingContent = { Icon(painterResource(R.drawable.ic_upload), contentDescription = null) },
			headingContent = { Text("Include pre-releases") },
			trailingContent = { Checkbox(checked = includePrereleases) },
			onClick = {
				includePrereleases = !includePrereleases
				appUpdater.includePrereleases = includePrereleases
			},
		)

		ListButton(
			enabled = !checking,
			leadingContent = {
				if (checking) CircularProgressIndicator(Modifier.size(20.dp))
				else Icon(painterResource(R.drawable.ic_upload), contentDescription = null)
			},
			headingContent = { Text("Check for updates") },
			captionContent = { updateMessage?.let { Text(it) } },
			onClick = {
				checking = true
				lifecycleScope.launch {
					when (val result = appUpdater.checkForUpdate(force = true)) {
						is UpdateCheckResult.Available -> {
							update = result.update
							notificationsRepository.updateAppUpdateNotification(result.update, prompt = false)
							updateMessage = "Update ${result.update.versionName} is available"
						}

						UpdateCheckResult.NoUpdate -> {
							update = null
							notificationsRepository.updateAppUpdateNotification(null, prompt = false)
							updateMessage = "No compatible update found"
						}

						UpdateCheckResult.Skipped -> updateMessage = "Checked recently"
						is UpdateCheckResult.Failed -> updateMessage = result.message
					}
					checking = false
				}
			},
		)

		update?.let { availableUpdate ->
			ListSection(
				overlineContent = { Text(if (availableUpdate.prerelease) "PRE-RELEASE" else "RELEASE") },
				headingContent = { Text("Update ${availableUpdate.versionName}") },
				captionContent = {
					Text("${availableUpdate.buildType} - ${Formatter.formatFileSize(context, availableUpdate.assetSize)}")
				},
			)

			if (availableUpdate.releaseNotes.isNotBlank()) {
				ListSection(
					headingContent = { Text("Release notes") },
					captionContent = { Text(availableUpdate.releaseNotes) },
				)
			}

			ListButton(
				enabled = !downloading,
				leadingContent = {
					if (downloading) CircularProgressIndicator(Modifier.size(20.dp))
					else Icon(painterResource(R.drawable.ic_upload), contentDescription = null)
				},
				headingContent = {
					Text(downloadProgress?.let { "Download and install $it%" } ?: "Download and install")
				},
				onClick = {
					downloading = true
					downloadProgress = 0
					lifecycleScope.launch {
						updateMessage = appUpdater.downloadAndStartInstall(
							availableUpdate,
							lifecycleScope.downloadProgressUpdater(
								isDownloading = { downloading },
								setProgress = { downloadProgress = it },
							),
						).message
						downloading = false
						downloadProgress = null
					}
				},
			)

			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
				headingContent = { Text("Open release page") },
				onClick = {
					context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(availableUpdate.releaseUrl)))
				},
			)
		}
	}
}

@Composable
private fun UpdatePromptContent(
	update: AppUpdate,
	downloading: Boolean,
	downloadProgress: Int?,
	message: String?,
	onUpdate: () -> Unit,
	onClose: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxHeight()
			.padding(Tokens.Space.spaceMd),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(
			text = if (update.prerelease) "PRE-RELEASE UPDATE" else "UPDATE AVAILABLE",
			style = JellyfinTheme.typography.listHeader.copy(color = JellyfinTheme.colorScheme.listHeader),
		)
		Text(
			text = update.versionName,
			fontSize = 20.sp,
			fontWeight = FontWeight.W700,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		Text("${update.buildType} build is ready to install.")

		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = "Changelog",
				fontWeight = FontWeight.W700,
			)
			Text(
				text = update.releaseNotes.ifBlank { "No release notes provided." },
				style = JellyfinTheme.typography.default.copy(color = JellyfinTheme.colorScheme.onBackground),
			)
		}

		message?.let { Text(it) }

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			if (downloading) {
				ProgressButton(
					progress = (downloadProgress ?: 0) / 100f,
					onClick = onUpdate,
					enabled = false,
					modifier = Modifier.weight(1f),
				) {
					Text(downloadProgress?.let { "Updating $it%" } ?: "Updating")
				}
			} else {
				Button(
					onClick = onUpdate,
					modifier = Modifier.weight(1f),
				) {
					Text("Update now")
				}
			}
			Button(
				onClick = onClose,
				enabled = !downloading,
				modifier = Modifier.weight(1f),
			) {
				Text("Close")
			}
		}
	}
}

private suspend fun AppUpdater.downloadAndStartInstall(
	update: AppUpdate,
	onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
): InstallOutcome {
	return when (val download = download(update, onProgress)) {
		is DownloadResult.Ready -> when (val install = startInstall(download.file)) {
			InstallStartResult.Started -> InstallOutcome("Installer opened", installerStarted = true)
			InstallStartResult.PermissionRequired -> {
				openInstallPermissionSettings()
				InstallOutcome("Allow installs from this app, then try again")
			}

			is InstallStartResult.Failed -> InstallOutcome(install.message)
		}

		is DownloadResult.Failed -> InstallOutcome(download.message)
	}
}

private fun downloadProgressPercent(downloaded: Long, total: Long): Int? =
	if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else null

private fun CoroutineScope.downloadProgressUpdater(
	isDownloading: () -> Boolean,
	setProgress: (Int) -> Unit,
): (downloaded: Long, total: Long) -> Unit {
	var lastProgress = -1
	return { downloaded, total ->
		val progress = downloadProgressPercent(downloaded, total)
		if (progress != null && progress != lastProgress) {
			lastProgress = progress
			launch {
				if (isDownloading()) setProgress(progress)
			}
		}
	}
}

private data class InstallOutcome(
	val message: String,
	val installerStarted: Boolean = false,
)
