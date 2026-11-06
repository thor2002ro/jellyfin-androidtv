package org.jellyfin.androidtv.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.updater.UpdatePromptContent
import org.jellyfin.design.Tokens
import org.jellyfin.updater.AppUpdate
import org.jellyfin.updater.AppUpdater
import org.jellyfin.updater.UpdateCheckResult
import org.koin.compose.koinInject

class UpdaterTestActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val initialScenario = intent.getStringExtra(EXTRA_SCENARIO)
			?.let { name -> runCatching { UpdaterTestScenario.valueOf(name) }.getOrNull() }
			?: UpdaterTestScenario.NO_UPDATE
		val inlinePrompt = intent.getBooleanExtra(EXTRA_INLINE_PROMPT, false)
		setContent {
			JellyfinTheme {
				UpdaterTestScreen(initialScenario, inlinePrompt)
			}
		}
	}

	companion object {
		const val EXTRA_SCENARIO = "scenario"
		const val EXTRA_INLINE_PROMPT = "inline_prompt"
	}
}

enum class UpdaterTestScenario(
	val label: String,
	val focusTransitions: Int = 0,
) {
	STABLE_UPDATE("Stable update"),
	PRERELEASE_UPDATE("Pre-release update"),
	NO_UPDATE("No update"),
	GITHUB_ERROR("GitHub error"),
	DOWNLOADING("Downloading"),
	DOWNLOAD_FAILED("Download failed"),
	INSTALL_PERMISSION("Install permission"),
	INSTALLER_OPENED("Installer opened"),
	POPUP_STABILITY("Popup stability", focusTransitions = 20),
}

@Composable
@Suppress("LongMethod")
private fun UpdaterTestScreen(
	initialScenario: UpdaterTestScenario,
	inlinePrompt: Boolean,
	appUpdater: AppUpdater = koinInject(),
) {
	var scenario by remember { mutableStateOf(initialScenario) }
	var popupVisible by remember { mutableStateOf(initialScenario.hasPrompt) }
	var liveResult by remember { mutableStateOf("Not run") }
	val scope = rememberCoroutineScope()

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(JellyfinTheme.colorScheme.background)
			.padding(Tokens.Space.spaceMd),
	) {
		Row(
			modifier = Modifier.fillMaxSize(),
			horizontalArrangement = Arrangement.spacedBy(Tokens.Space.spaceMd),
		) {
			Column(
				modifier = Modifier
					.weight(0.45f)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs),
			) {
				ListSection(
					overlineContent = { Text("DEBUG") },
					headingContent = { Text("GitHub updater tester") },
					captionContent = { Text("No scenario downloads or installs an APK") },
				)
				UpdaterTestScenario.entries.forEach { candidate ->
					ListButton(
						headingContent = { Text(candidate.label) },
						captionContent = if (candidate == scenario) ({ Text("Selected") }) else null,
						onClick = {
							scenario = candidate
							popupVisible = candidate.hasPrompt
						},
					)
				}
				ListButton(
					headingContent = { Text("Live GitHub check") },
					captionContent = { Text(liveResult) },
					onClick = {
						liveResult = "Checking…"
						scope.launch {
							liveResult = when (val result = appUpdater.checkForUpdate(force = true)) {
								is UpdateCheckResult.Available -> "Found ${result.update.versionName} (${result.update.assetName})"
								UpdateCheckResult.NoUpdate -> "No compatible update found"
								UpdateCheckResult.Skipped -> "Check skipped"
								is UpdateCheckResult.Failed -> result.message
							}
						}
					},
				)
			}

			if (popupVisible && inlinePrompt) {
				UpdaterScenarioPrompt(
					scenario = scenario,
					onClose = { popupVisible = false },
					inline = true,
					modifier = Modifier.weight(0.55f),
				)
			} else {
				ScenarioSummary(
					scenario = scenario,
					modifier = Modifier.weight(0.55f),
				)
			}
		}

		if (popupVisible && !inlinePrompt) {
			UpdaterScenarioPrompt(
				scenario = scenario,
				onClose = { popupVisible = false },
				inline = false,
			)
		}
	}
}

@Composable
private fun ScenarioSummary(scenario: UpdaterTestScenario, modifier: Modifier = Modifier) {
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
	) {
		ListSection(
			overlineContent = { Text("CURRENT SCENARIO") },
			headingContent = { Text(scenario.label) },
			captionContent = { Text(scenario.summary) },
		)
		when (scenario) {
			UpdaterTestScenario.NO_UPDATE -> TestStatus("PASS", "No compatible update found")
			UpdaterTestScenario.GITHUB_ERROR -> TestStatus("RECOVERABLE ERROR", "GitHub update check failed (HTTP 503). Please retry later.")
			UpdaterTestScenario.INSTALLER_OPENED -> TestStatus("PASS", "Installer opened; production would dismiss the prompt")
			else -> TestStatus("READY", "Select the scenario to open its production-style prompt")
		}
	}
}

@Composable
private fun TestStatus(status: String, detail: String) {
	ListSection(
		overlineContent = { Text(status) },
		headingContent = { Text(detail) },
	)
}

@Composable
private fun UpdaterScenarioPrompt(
	scenario: UpdaterTestScenario,
	onClose: () -> Unit,
	inline: Boolean,
	modifier: Modifier = Modifier,
) {
	val update = testUpdate(prerelease = scenario == UpdaterTestScenario.PRERELEASE_UPDATE)
	var mountedCount by remember(scenario) { mutableIntStateOf(0) }
	var fakeHostFocused by remember(scenario) { mutableStateOf(true) }
	var stabilityResult by remember(scenario) { mutableStateOf<String?>(null) }

	LaunchedEffect(scenario) {
		if (scenario == UpdaterTestScenario.POPUP_STABILITY) {
			repeat(scenario.focusTransitions) {
				fakeHostFocused = !fakeHostFocused
				yield()
			}
			stabilityResult = if (mountedCount == 1) {
				"PASS: Update now stayed mounted through ${scenario.focusTransitions} focus transitions"
			} else {
				"FAIL: popup mounted $mountedCount times"
			}
		}
	}

	val content = @Composable {
		DisposableEffect(Unit) {
			mountedCount++
			onDispose { }
		}
		Box(modifier = modifier) {
			UpdatePromptContent(
				update = update,
				downloading = scenario == UpdaterTestScenario.DOWNLOADING,
				downloadProgress = if (scenario == UpdaterTestScenario.DOWNLOADING) 42 else null,
				message = when (scenario) {
					UpdaterTestScenario.DOWNLOAD_FAILED -> "Unable to download the update: simulated connection failure"
					UpdaterTestScenario.INSTALL_PERMISSION -> "Allow installs from this app, then try again"
					UpdaterTestScenario.INSTALLER_OPENED -> "Installer opened"
					UpdaterTestScenario.POPUP_STABILITY -> stabilityResult ?: "Simulating host focus: $fakeHostFocused"
					else -> null
				},
				onUpdate = { },
				onClose = onClose,
			)
		}
	}

	if (inline) {
		content()
	} else {
		SettingsDialog(
			visible = true,
			onDismissRequest = onClose,
		) {
			content()
		}
	}
}

private val UpdaterTestScenario.hasPrompt
	get() = this !in setOf(UpdaterTestScenario.NO_UPDATE, UpdaterTestScenario.GITHUB_ERROR)

private val UpdaterTestScenario.summary
	get() = when (this) {
		UpdaterTestScenario.STABLE_UPDATE -> "Stable release metadata, notes, and Update now action"
		UpdaterTestScenario.PRERELEASE_UPDATE -> "Pre-release warning and update action"
		UpdaterTestScenario.NO_UPDATE -> "Successful check with no newer compatible APK"
		UpdaterTestScenario.GITHUB_ERROR -> "Recoverable GitHub API failure"
		UpdaterTestScenario.DOWNLOADING -> "Disabled actions and deterministic 42% progress"
		UpdaterTestScenario.DOWNLOAD_FAILED -> "Download error with retry action restored"
		UpdaterTestScenario.INSTALL_PERMISSION -> "Unknown-app install permission recovery"
		UpdaterTestScenario.INSTALLER_OPENED -> "Successful handoff to Android's installer"
		UpdaterTestScenario.POPUP_STABILITY -> "Twenty focus transitions without unmounting Update now"
	}

private fun testUpdate(prerelease: Boolean) = AppUpdate(
	versionName = if (prerelease) "2026.09.12-beta.1" else "2026.09.12",
	buildType = "debug",
	releaseName = "Updater tester fixture",
	tagName = if (prerelease) "v2026.09.12-beta.1" else "v2026.09.12",
	releaseUrl = "https://github.com/thor2002ro/jellyfin-androidtv/releases",
	releaseNotes = "Deterministic release notes for TV layout, scrolling, focus, and action validation.",
	prerelease = prerelease,
	publishedAt = "2026-09-11T00:00:00Z",
	assetName = "jellyfin-androidtv-thor-updater-test-universal-debug.apk",
	assetUrl = "https://example.invalid/updater-test.apk",
	assetSize = 42_000_000,
	assetDigest = "sha256:${"0".repeat(64)}",
)
