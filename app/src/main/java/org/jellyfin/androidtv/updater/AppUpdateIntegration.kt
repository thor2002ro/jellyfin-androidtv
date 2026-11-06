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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
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
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.design.Tokens
import org.jellyfin.updater.AppUpdate
import org.jellyfin.updater.AppUpdater
import org.jellyfin.updater.UpdateCheckResult
import org.koin.compose.koinInject

fun startAppUpdateCheck(
	appUpdater: AppUpdater,
	notificationsRepository: NotificationsRepository,
) {
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		appUpdater.clearUpdateCacheAtStartup()
		val result = appUpdater.checkForUpdate(force = false)
		if (appUpdater.checkState.value.result != result) return@launch
		when (result) {
			is UpdateCheckResult.Available -> notificationsRepository.updateAppUpdateNotification(result.update)
			else -> Unit
		}
	}
}

@Composable
fun AppUpdatePrompt(
	notificationsRepository: NotificationsRepository = koinInject(),
	appUpdater: AppUpdater = koinInject(),
	navigationRepository: NavigationRepository = koinInject(),
) {
	val update by notificationsRepository.appUpdatePrompt.collectAsState()
	val destination by navigationRepository.currentDestination.collectAsState()
	val hostWindowFocused = LocalWindowInfo.current.isWindowFocused
	var promptedUpdate by remember { mutableStateOf<AppUpdate?>(null) }
	val availableUpdate = appUpdatePrompt(
		update = update,
		currentPrompt = promptedUpdate,
		isPlayback = Destinations.isPlayback(destination),
		hostWindowFocused = hostWindowFocused,
	)
	SideEffect { promptedUpdate = availableUpdate }
	availableUpdate ?: return
	val operation by appUpdater.operationState.collectAsState()
	val updateScope = ProcessLifecycleOwner.get().lifecycleScope
	val canInstallNow = rememberInstallEligibility()
	LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appUpdater.resumeInstallAfterPermission(canInstallNow) }

	SettingsDialog(
		visible = true,
		onDismissRequest = { notificationsRepository.dismissAppUpdatePrompt() },
	) {
		UpdatePromptContent(
			update = availableUpdate,
			downloading = operation.downloading,
			downloadProgress = operation.progress,
			message = operation.message.takeIf { operation.update == availableUpdate },
			readyToInstall = operation.update == availableUpdate && operation.file != null,
			onUpdate = {
				updateScope.launch { appUpdater.downloadAndInstall(availableUpdate, canInstallNow) }
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
	val updateScope = ProcessLifecycleOwner.get().lifecycleScope
	val availableAppUpdate by notificationsRepository.appUpdate.collectAsState()
	val operation by appUpdater.operationState.collectAsState()
	val check by appUpdater.checkState.collectAsState()
	var includePrereleases by remember { mutableStateOf(appUpdater.includePrereleases) }
	var useUniversalApk by remember { mutableStateOf<Boolean?>(null) }
	val checking = check.checking
	val downloading = operation.downloading
	val downloadProgress = operation.progress
	val update = availableAppUpdate
	val updateMessage = when (val result = check.result) {
		is UpdateCheckResult.Available -> "Update ${result.update.versionName} is available"
		UpdateCheckResult.NoUpdate -> "No compatible update found"
		UpdateCheckResult.Skipped -> "Checked recently"
		is UpdateCheckResult.Failed -> result.message
		null -> null
	}
	val canInstallNow = rememberInstallEligibility()

	LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appUpdater.resumeInstallAfterPermission(canInstallNow) }
	LaunchedEffect(Unit) {
		useUniversalApk = appUpdater.initializeUseUniversalApk()
	}

	val checkForUpdates: () -> Unit = {
		updateScope.launch {
			val result = appUpdater.checkForUpdate(force = true)
			if (appUpdater.checkState.value.result != result) return@launch
			when (result) {
				is UpdateCheckResult.Available -> notificationsRepository.updateAppUpdateNotification(result.update, prompt = false)
				UpdateCheckResult.NoUpdate -> notificationsRepository.updateAppUpdateNotification(null, prompt = false)
				else -> Unit
			}
		}
	}

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs),
	) {
		ListButton(
			enabled = !checking && !downloading,
			leadingContent = { Icon(painterResource(R.drawable.ic_upload), contentDescription = null) },
			headingContent = { Text("Include pre-releases") },
			trailingContent = { Checkbox(checked = includePrereleases) },
			onClick = {
				val current = appUpdater.includePrereleases
				appUpdater.includePrereleases = !current
				includePrereleases = appUpdater.includePrereleases
				if (includePrereleases != current) {
					notificationsRepository.updateAppUpdateNotification(null, prompt = false)
					checkForUpdates()
				}
			},
		)

		ListButton(
			enabled = !checking && !downloading && appUpdater.deviceAbi != null && useUniversalApk != null,
			headingContent = { Text("Update package") },
			captionContent = {
				Text(when (useUniversalApk) {
					null -> "Detecting installed APK"
					true -> "Universal APK"
					false -> "${appUpdater.deviceAbi} APK"
				})
			},
			onClick = {
				useUniversalApk?.let { current ->
					appUpdater.useUniversalApk = !current
					useUniversalApk = appUpdater.useUniversalApk
					if (useUniversalApk != current) {
						notificationsRepository.updateAppUpdateNotification(null, prompt = false)
						checkForUpdates()
					}
				}
			},
		)

		ListButton(
			enabled = !checking && !downloading,
			leadingContent = {
				if (checking) CircularProgressIndicator(Modifier.size(20.dp))
				else Icon(painterResource(R.drawable.ic_upload), contentDescription = null)
			},
			headingContent = { Text("Check for updates") },
			captionContent = { updateMessage?.let { Text(it) } },
			onClick = checkForUpdates,
		)

		update?.let { availableUpdate ->
			val readyToInstall = operation.update == availableUpdate && operation.file != null
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
				enabled = !checking && !downloading,
				leadingContent = {
					if (downloading) CircularProgressIndicator(Modifier.size(20.dp))
					else Icon(painterResource(R.drawable.ic_upload), contentDescription = null)
				},
				headingContent = {
					Text(when {
						downloading -> downloadProgress?.let { "Downloading $it%" } ?: "Downloading"
						readyToInstall -> "Install again"
						else -> "Download and install"
					})
				},
				captionContent = { operation.message.takeIf { operation.update == availableUpdate }?.let { Text(it) } },
				onClick = {
					updateScope.launch { appUpdater.downloadAndInstall(availableUpdate, canInstallNow) }
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
internal fun UpdatePromptContent(
	update: AppUpdate,
	downloading: Boolean,
	downloadProgress: Int?,
	message: String?,
	onUpdate: () -> Unit,
	onClose: () -> Unit,
	readyToInstall: Boolean = false,
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
		Text("${update.buildType} build is available.")

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
					Text(if (readyToInstall) "Install again" else "Update now")
				}
			}
			Button(
				onClick = onClose,
				modifier = Modifier.weight(1f),
			) {
				Text("Close")
			}
		}
	}
}

@Composable
private fun rememberInstallEligibility(navigationRepository: NavigationRepository = koinInject()): () -> Boolean {
	val owner = LocalLifecycleOwner.current
	val activity = LocalContext.current.getActivity()
	return remember(owner, activity, navigationRepository) {
		{
			appUpdateInstallAllowed(
				isPlayback = Destinations.isPlayback(navigationRepository.currentDestination.value),
				hostResumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
				hostAvailable = activity != null && !activity.isFinishing && !activity.isDestroyed,
			)
		}
	}
}

internal fun appUpdateInstallAllowed(isPlayback: Boolean, hostResumed: Boolean, hostAvailable: Boolean) =
	hostAvailable && hostResumed && !isPlayback

internal fun appUpdatePrompt(
	update: AppUpdate?,
	currentPrompt: AppUpdate?,
	isPlayback: Boolean,
	hostWindowFocused: Boolean,
) = when {
	update == null || isPlayback -> null
	currentPrompt != null || hostWindowFocused -> update
	else -> null
}
