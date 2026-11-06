package org.jellyfin.androidtv.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

class UpdaterUiSuite(private val instrumentation: Instrumentation) {
	fun run(scenarioFilter: String?): List<PlaybackTestResult> {
		prewarmAccessibility()
		return scenarios
			.asSequence()
			.filter { (scenario) -> scenarioFilter == null || scenario.name.equals(scenarioFilter, ignoreCase = true) }
			.map(::verify)
			.toList()
	}

	private fun prewarmAccessibility() {
		val activity = instrumentation.startActivitySync(
			Intent(instrumentation.targetContext, UpdaterTestActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.putExtra(UpdaterTestActivity.EXTRA_SCENARIO, UpdaterTestScenario.NO_UPDATE.name)
		)
		val deadline = SystemClock.uptimeMillis() + 2_000
		do {
			instrumentation.waitForIdleSync()
			val automation = instrumentation.uiAutomation
			if (automation.rootInActiveWindow != null || automation.windows.isNotEmpty()) break
			SystemClock.sleep(50)
		} while (SystemClock.uptimeMillis() < deadline)
		instrumentation.runOnMainSync(activity::finish)
		instrumentation.waitForIdleSync()
	}

	private fun verify(test: UpdaterUiExpectation): PlaybackTestResult {
		val activity = instrumentation.startActivitySync(
			Intent(instrumentation.targetContext, UpdaterTestActivity::class.java)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.putExtra(UpdaterTestActivity.EXTRA_SCENARIO, test.scenario.name)
				.putExtra(UpdaterTestActivity.EXTRA_INLINE_PROMPT, true)
		)
		return try {
			val missing = test.expectedText.firstOrNull { text -> !waitForText(text) }
			if (missing == null) {
				PlaybackTestResult(
					PlaybackTestStatus.PASS,
					"updater",
					scenario = test.scenario.name.lowercase(),
					detail = test.expectedText.joinToString(prefix = "visible=", separator = " | "),
				)
			} else {
				PlaybackTestResult(
					PlaybackTestStatus.FAIL,
					"updater",
					scenario = test.scenario.name.lowercase(),
					detail = "missing='$missing' visible='${visibleText()}'",
				)
			}
		} finally {
			instrumentation.runOnMainSync(activity::finish)
			instrumentation.waitForIdleSync()
		}
	}

	private fun waitForText(text: String): Boolean {
		val deadline = SystemClock.uptimeMillis() + 10_000
		do {
			instrumentation.waitForIdleSync()
			val automation = instrumentation.uiAutomation
			if (
				automation.rootInActiveWindow?.containsText(text) == true ||
				automation.windows.any { window -> window.root?.containsText(text) == true }
			) return true
			SystemClock.sleep(50)
		} while (SystemClock.uptimeMillis() < deadline)
		return false
	}

	private fun visibleText(): String {
		val automation = instrumentation.uiAutomation
		val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { window -> window.root }
		return roots
			.flatMap(AccessibilityNodeInfo::allText)
			.distinct()
			.joinToString(separator = " | ")
			.take(1_000)
	}
}

private data class UpdaterUiExpectation(
	val scenario: UpdaterTestScenario,
	val expectedText: List<String>,
)

private val scenarios = listOf(
	UpdaterUiExpectation(UpdaterTestScenario.NO_UPDATE, listOf("No compatible update found")),
	UpdaterUiExpectation(UpdaterTestScenario.GITHUB_ERROR, listOf("GitHub update check failed (HTTP 503)")),
	UpdaterUiExpectation(UpdaterTestScenario.STABLE_UPDATE, listOf("UPDATE AVAILABLE", "Update now")),
	UpdaterUiExpectation(UpdaterTestScenario.PRERELEASE_UPDATE, listOf("PRE-RELEASE UPDATE", "Update now")),
	UpdaterUiExpectation(UpdaterTestScenario.DOWNLOADING, listOf("Updating 42%")),
	UpdaterUiExpectation(UpdaterTestScenario.DOWNLOAD_FAILED, listOf("simulated connection failure", "Update now")),
	UpdaterUiExpectation(UpdaterTestScenario.INSTALL_PERMISSION, listOf("Allow installs from this app", "Update now")),
	UpdaterUiExpectation(UpdaterTestScenario.INSTALLER_OPENED, listOf("Installer opened", "Update now")),
	UpdaterUiExpectation(UpdaterTestScenario.POPUP_STABILITY, listOf("PASS: Update now stayed mounted", "Update now")),
)

private fun AccessibilityNodeInfo.containsText(expected: String): Boolean {
	if (text?.contains(expected, ignoreCase = false) == true) return true
	if (contentDescription?.contains(expected, ignoreCase = false) == true) return true
	return (0 until childCount).any { index -> getChild(index)?.containsText(expected) == true }
}

private fun AccessibilityNodeInfo.allText(): List<String> = buildList {
	text?.toString()?.takeIf(String::isNotBlank)?.let(::add)
	contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(::add)
	for (index in 0 until childCount) addAll(getChild(index)?.allText().orEmpty())
}
