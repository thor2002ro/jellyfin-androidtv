package org.jellyfin.androidtv.util.profile

import android.os.Build
import org.jellyfin.playback.dovi.DoviWorkarounds

internal fun interface DoviWorkaroundRuleSource {
	fun rules(): List<DoviWorkaroundProvider.Rule>

	data object Empty : DoviWorkaroundRuleSource {
		override fun rules() = emptyList<DoviWorkaroundProvider.Rule>()
	}
}

/** Deterministic device workaround hook; unknown devices use no compatibility repairs. */
internal class DoviWorkaroundProvider(
	private val manufacturer: String = Build.MANUFACTURER.orEmpty(),
	private val model: String = Build.MODEL.orEmpty(),
	private val features: Set<Feature> = emptySet(),
	private val ruleSource: DoviWorkaroundRuleSource = DoviWorkaroundRuleSource.Empty,
) {
	enum class Feature { CMV40_LIMITED, ACTIVE_AREA_LIMITED }

	data class Rule(
		val manufacturer: String? = null,
		val model: String? = null,
		val requiredFeatures: Set<Feature> = emptySet(),
		val workarounds: DoviWorkarounds,
	)

	fun resolve(): DoviWorkarounds = ruleSource.rules().firstOrNull { rule ->
		rule.manufacturer.matchesIfPresent(manufacturer) &&
			rule.model.matchesIfPresent(model) &&
			features.containsAll(rule.requiredFeatures)
	}?.workarounds ?: DoviWorkarounds()

	private fun String?.matchesIfPresent(actual: String): Boolean =
		this == null || trim().equals(actual.trim(), ignoreCase = true)
}
