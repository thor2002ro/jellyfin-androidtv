package org.jellyfin.androidtv.ui.home

import org.jellyfin.androidtv.constant.HomeSectionType

internal data class HomeSectionLayout(
	val sections: List<HomeSectionType>,
	val combineContinueWatchingAndNextUp: Boolean,
)

internal fun createHomeSectionLayout(
	sections: Collection<HomeSectionType>,
	combineContinueWatchingAndNextUp: Boolean,
): HomeSectionLayout {
	val sectionList = sections.toList()
	val canCombine = combineContinueWatchingAndNextUp &&
		HomeSectionType.RESUME in sectionList &&
		HomeSectionType.NEXT_UP in sectionList

	if (!canCombine) return HomeSectionLayout(sectionList, false)

	var combinedSectionAdded = false
	val combinedSections = sectionList.mapNotNull { section ->
		when {
			section != HomeSectionType.RESUME && section != HomeSectionType.NEXT_UP -> section
			combinedSectionAdded -> null
			else -> HomeSectionType.RESUME.also { combinedSectionAdded = true }
		}
	}

	return HomeSectionLayout(combinedSections, true)
}
