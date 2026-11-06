package org.jellyfin.androidtv.ui.browsing

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.os.BundleCompat
import androidx.core.os.ParcelCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragment
import org.jellyfin.androidtv.ui.itemdetail.ItemListFragment
import org.jellyfin.androidtv.ui.navigation.Destination
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.util.createBundle
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.Stack
import java.util.UUID
import kotlin.jvm.kotlin

private class HistoryEntry(
	val name: Class<out Fragment>,
	val arguments: Bundle = createBundle(),

	var fragment: Fragment? = null,
	var savedState: Fragment.SavedState? = null,
	var focusedView: View? = null,
) : Parcelable {
	override fun describeContents(): Int = 0

	override fun writeToParcel(dest: Parcel, flags: Int) {
		dest.writeString(name.name)
		dest.writeBundle(arguments)
		dest.writeParcelable(savedState, 0)
	}

	companion object CREATOR : Parcelable.Creator<HistoryEntry> {
		@Suppress("UNCHECKED_CAST")
		override fun createFromParcel(parcel: Parcel): HistoryEntry = HistoryEntry(
			name = Class.forName(parcel.readString()!!) as Class<out Fragment>,
			arguments = parcel.readBundle(this::class.java.classLoader)!!,
			fragment = null,
			savedState = ParcelCompat.readParcelable(parcel, this::class.java.classLoader, Fragment.SavedState::class.java)!!,
		)

		override fun newArray(size: Int): Array<HistoryEntry?> = arrayOfNulls(size)
	}
}

class DestinationFragmentView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
	private companion object {
		private const val FRAGMENT_TAG_CONTENT = "content"
		private const val BUNDLE_SUPER = "super"
		private const val BUNDLE_HISTORY = "history"
		private val LIBRARY_SCREENS = setOf(
			BrowseGridFragment::class.java,
			BrowseViewFragment::class.java,
			GenericFolderFragment::class.java,
			CollectionFragment::class.java,
			ByGenreFragment::class.java,
			ByLetterFragment::class.java,
			SuggestedMoviesFragment::class.java,
			FullDetailsFragment::class.java,
			ItemListFragment::class.java,
		)
	}

	private val fragmentManager by lazy {
		FragmentManager.findFragmentManager(this)
	}

	private val container by lazy {
		FragmentContainerView(context).also { view ->
			view.id = R.id.container
			addView(view)
		}
	}

	private val history = Stack<HistoryEntry>()
	private var activeLibraryId: UUID? = null
	var onDestinationChanged: ((Destination.Fragment) -> Unit)? = null

	fun navigate(action: NavigationAction.NavigateFragment) {
		val entry = HistoryEntry(action.destination.fragment.java, action.destination.arguments)

		// Create the base transaction so we can mutate everything at once
		val transaction = fragmentManager.beginTransaction()

		if (action.clear) {
			// Clear all current fragments from the history before adding the new entry
			history.mapNotNull { it.fragment }.forEach { transaction.remove(it) }
			history.clear()
			history.push(entry)
		} else if (action.replace && action.addToBackStack && history.isNotEmpty()) {
			// Remove the top-most entry before replacing it with the next
			val currentFragment = history[history.size - 1].fragment
			if (currentFragment != null) transaction.remove(currentFragment)
			history[history.size - 1] = entry
		} else {
			// Add to the end of the history
			saveCurrentFragmentState()
			history.push(entry)
		}

		activateHistoryEntry(entry, transaction)
	}

	fun goBack(): Boolean {
		// Require at least 2 items (current & previous) to go back
		if (history.size < 2) return false

		// Create the base transaction so we can mutate everything at once
		val transaction = fragmentManager.beginTransaction()

		// Remove current entry
		val currentEntry = history.pop()

		// Make sure to remove the associated fragment
		val currentFragment = currentEntry.fragment
		if (currentFragment != null) transaction.remove(currentFragment)

		// Read & set previous entry
		val entry = history.last()
		activateHistoryEntry(entry, transaction)

		return true
	}

	private fun saveCurrentFragmentState() {
		if (history.isEmpty()) return

		// Update the top-most history entry with state from current fragment
		val fragment = requireNotNull(fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT))
		history[history.size - 1].savedState = fragmentManager.saveFragmentInstanceState(fragment)
	}

	private fun libraryId(): UUID? {
		for (entry in history.asReversed()) {
			if (entry.name !in LIBRARY_SCREENS && !Destinations.isPlayback(Destination.Fragment(entry.name.kotlin, entry.arguments))) return null
			val folder = entry.arguments.getString(Extras.Folder)?.let { Json.decodeFromString<BaseItemDto>(it) } ?: continue
			if (folder.type == BaseItemKind.USER_VIEW || folder.type == BaseItemKind.COLLECTION_FOLDER) return folder.id
		}
		return null
	}

	@SuppressLint("CommitTransaction")
	private fun activateHistoryEntry(
		entry: HistoryEntry,
		transaction: FragmentTransaction,
	) {
		var fragment = entry.fragment

		// Create if there is no existing fragment
		if (fragment == null) {
			fragment = fragmentManager.fragmentFactory.instantiate(context.classLoader, entry.name.name).apply {
				// A new, unattached fragment can receive arguments even after state was saved.
				// Otherwise commitAllowingStateLoss() below could open it without its item ID.
				arguments = entry.arguments
				setInitialSavedState(entry.savedState)
			}
			entry.fragment = fragment
		}

		// Existing fragments may only have their arguments updated before state is saved.
		if (!fragmentManager.isStateSaved) {
			fragment.arguments = entry.arguments
		}

		val nextLibraryId = libraryId()
		val keepLibraryViews = nextLibraryId != null && nextLibraryId == activeLibraryId
		transaction.apply {
			// Set options
			setReorderingAllowed(true)
			setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)

			val current = fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT)
			val currentEntry = history.firstOrNull { it.fragment === current }
			if (!keepLibraryViews) {
				// Crossing a library boundary releases its retained views while keeping normal Back history.
				for (previous in history) {
					val retained = previous.fragment ?: continue
					if (!retained.isHidden || retained === fragment) continue
					previous.focusedView = null
					detach(retained)
					show(retained)
					setMaxLifecycle(retained, Lifecycle.State.RESUMED)
				}
			}
			if (keepLibraryViews && currentEntry != null && current != null && current.javaClass in LIBRARY_SCREENS) {
				// Keep only the current library's Back path ready to show without rebuilding its cards.
				currentEntry.focusedView = current.view?.findFocus()
				hide(current)
				setMaxLifecycle(current, Lifecycle.State.STARTED)
			} else {
				current?.let(::detach)
			}

			// Attach or add next fragment
			if (fragment.isHidden) {
				show(fragment)
				setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
				val focusedView = entry.focusedView
				entry.focusedView = null
				runOnCommit { if (focusedView?.requestFocus() != true) fragment.view?.requestFocus() }
			} else if (fragment.isDetached) attach(fragment)
			else add(container.id, fragment, FRAGMENT_TAG_CONTENT)
		}
		activeLibraryId = nextLibraryId

		if (fragmentManager.isDestroyed) {
			Timber.w("FragmentManager is already destroyed")
		} else if (fragmentManager.isStateSaved) {
			transaction.commitAllowingStateLoss()
		} else {
			transaction.commit()
		}

		onDestinationChanged?.invoke(Destination.Fragment(entry.name.kotlin, entry.arguments))
	}

	override fun onSaveInstanceState(): Parcelable {
		// Always retrieve current state before writing
		saveCurrentFragmentState()

		// Save state
		return createBundle {
			putParcelable(BUNDLE_SUPER, super.onSaveInstanceState())
			putParcelableArrayList(BUNDLE_HISTORY, ArrayList(history))
		}
	}

	override fun onRestoreInstanceState(state: Parcelable?) {
		// Ignore if not a bundle
		if (state !is Bundle) return super.onRestoreInstanceState(state)

		// Call parent
		@Suppress("DEPRECATION")
		val parent = state.getParcelable<Parcelable>(BUNDLE_SUPER)
		super.onRestoreInstanceState(parent)

		// Restore history
		val savedHistory = BundleCompat.getParcelableArrayList(state, BUNDLE_HISTORY, HistoryEntry::class.java)
		if (savedHistory != null) {
			history.clear()
			history.addAll(savedHistory)
			if (history.isNotEmpty()) activateHistoryEntry(history.last(), fragmentManager.beginTransaction())
		}
	}
}
