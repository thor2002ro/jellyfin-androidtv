package org.jellyfin.androidtv.ui.playback.overlay

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.children
import org.jellyfin.androidtv.R
import kotlin.math.roundToInt

object PlaybackControlTooltips {
	private const val TOOLTIP_DELAY_MS = 650L
	private const val TOOLTIP_HORIZONTAL_MARGIN_DP = 8
	private const val TOOLTIP_VERTICAL_MARGIN_DP = 12

	private val handler = Handler(Looper.getMainLooper())
	private var pendingTooltip: Runnable? = null
	private var popup: PopupWindow? = null

	@JvmStatic
	fun install(root: View) {
		if (root.getTag(R.id.playback_control_tooltips_installed) == true) return
		root.setTag(R.id.playback_control_tooltips_installed, true)

		val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
			if (newFocus != null && root.contains(newFocus)) {
				val label = newFocus.tooltipLabel()
				if (label == null) dismiss() else schedule(newFocus, label)
			} else {
				dismiss()
			}
		}

		root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
		root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
			override fun onViewAttachedToWindow(view: View) = Unit

			override fun onViewDetachedFromWindow(view: View) {
				dismiss()
				if (root.viewTreeObserver.isAlive) {
					root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
				}
			}
		})

		root.post { installHoverTooltips(root) }
	}

	private fun installHoverTooltips(view: View) {
		if (view.getTag(R.id.playback_control_hover_tooltip_installed) == true) return
		view.setTag(R.id.playback_control_hover_tooltip_installed, true)

		if ((view.isFocusable || view.isClickable) && view.tooltipLabel() != null) {
			view.setOnHoverListener { hoveredView, event ->
				when (event.actionMasked) {
					MotionEvent.ACTION_HOVER_ENTER -> {
						hoveredView.tooltipLabel()?.let { schedule(hoveredView, it) }
						false
					}

					MotionEvent.ACTION_HOVER_EXIT -> {
						dismiss()
						false
					}

					else -> false
				}
			}
		}

		(view as? ViewGroup)?.children?.forEach(::installHoverTooltips)
	}

	private fun schedule(anchor: View, label: String) {
		dismiss()
		pendingTooltip = Runnable {
			if (anchor.isAttachedToWindow) show(anchor, label)
		}.also { handler.postDelayed(it, TOOLTIP_DELAY_MS) }
	}

	private fun show(anchor: View, label: String) {
		val context = anchor.context
		val density = context.resources.displayMetrics.density
		val horizontalMargin = (TOOLTIP_HORIZONTAL_MARGIN_DP * density).roundToInt()
		val verticalMargin = (TOOLTIP_VERTICAL_MARGIN_DP * density).roundToInt()

		val textView = TextView(context).apply {
			text = label
			setTextColor(Color.WHITE)
			textSize = 14f
			setPadding(
				(12 * density).roundToInt(),
				(8 * density).roundToInt(),
				(12 * density).roundToInt(),
				(8 * density).roundToInt(),
			)
			background = GradientDrawable().apply {
				shape = GradientDrawable.RECTANGLE
				cornerRadius = 4 * density
				setColor(Color.rgb(25, 31, 40))
			}
		}

		val tooltip = PopupWindow(
			textView,
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
			false,
		).apply {
			isClippingEnabled = true
			elevation = 4 * density
		}

		textView.measure(
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
		)

		val anchorLocation = IntArray(2)
		val rootLocation = IntArray(2)
		val root = anchor.rootView
		anchor.getLocationOnScreen(anchorLocation)
		root.getLocationOnScreen(rootLocation)

		val tooltipWidth = textView.measuredWidth
		val tooltipHeight = textView.measuredHeight
		val minX = rootLocation[0] + horizontalMargin
		val maxX = rootLocation[0] + root.width - tooltipWidth - horizontalMargin
		val x = (anchorLocation[0] + (anchor.width - tooltipWidth) / 2).coerceInOrStart(minX, maxX)
		val aboveY = anchorLocation[1] - tooltipHeight - verticalMargin
		val belowY = anchorLocation[1] + anchor.height + verticalMargin
		val y = if (aboveY >= rootLocation[1]) aboveY else belowY

		popup = tooltip
		tooltip.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
	}

	private fun dismiss() {
		pendingTooltip?.let(handler::removeCallbacks)
		pendingTooltip = null
		popup?.dismiss()
		popup = null
	}

	private fun View.tooltipLabel(): String? {
		contentDescription?.toString()?.takeIf(String::isNotBlank)?.let { return it }
		if (this is TextView) text?.toString()?.takeIf(String::isNotBlank)?.let { return it }
		return (this as? ViewGroup)
			?.children
			?.mapNotNull { child -> child.tooltipLabel() }
			?.firstOrNull()
	}

	private fun View.contains(child: View): Boolean {
		var current: View? = child
		while (current != null) {
			if (current == this) return true
			current = current.parent as? View
		}
		return false
	}

	private fun Int.coerceInOrStart(minimumValue: Int, maximumValue: Int) =
		if (minimumValue <= maximumValue) coerceIn(minimumValue, maximumValue) else minimumValue
}
