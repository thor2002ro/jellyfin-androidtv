package org.jellyfin.androidtv.ui.settings.screen.playback

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.LibMPVBackendSettings
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.LibMPVChoiceSetting
import org.jellyfin.androidtv.preference.constant.LibMPVPreferenceOption
import org.jellyfin.androidtv.preference.constant.resetLibMPVPreferences
import org.jellyfin.androidtv.preference.mpvAudioChannels
import org.jellyfin.androidtv.preference.mpvAudioOutput
import org.jellyfin.androidtv.preference.mpvAudioPitchCorrection
import org.jellyfin.androidtv.preference.mpvAudioSpdif
import org.jellyfin.androidtv.preference.mpvDeband
import org.jellyfin.androidtv.preference.mpvDecoder
import org.jellyfin.androidtv.preference.mpvDecoderThreads
import org.jellyfin.androidtv.preference.mpvFrameDrop
import org.jellyfin.androidtv.preference.mpvGpuApi
import org.jellyfin.androidtv.preference.mpvGpuContext
import org.jellyfin.androidtv.preference.mpvInterpolation
import org.jellyfin.androidtv.preference.mpvLoopFilter
import org.jellyfin.androidtv.preference.mpvOptionOverrides
import org.jellyfin.androidtv.preference.mpvReplayGain
import org.jellyfin.androidtv.preference.mpvScaler
import org.jellyfin.androidtv.preference.mpvSubtitleAssOverride
import org.jellyfin.androidtv.preference.mpvSubtitleUseMargins
import org.jellyfin.androidtv.preference.mpvToneMapping
import org.jellyfin.androidtv.preference.mpvVideoOutput
import org.jellyfin.androidtv.preference.mpvVideoSync
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.form.RangeControl
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListControl
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.playback.mpv.LibMPVOptionInfo
import org.jellyfin.playback.mpv.LibMPVOptionValueError
import org.jellyfin.playback.mpv.isLibMPVOptionManagedByJellyfin
import org.jellyfin.playback.mpv.normalizeLibMPVOptionValue
import org.jellyfin.playback.mpv.parseLibMPVOptionOverrides
import org.jellyfin.playback.mpv.serializeLibMPVOptionOverrides
import org.jellyfin.playback.mpv.validateLibMPVOptionValue
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun SettingsPlaybackLibMPVScreen() {
	val context = LocalContext.current
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val backendSettings = koinInject<LibMPVBackendSettings>()
	val decoder by rememberPreference(userPreferences, UserPreferences.mpvDecoder)
	val videoOutput by rememberPreference(userPreferences, UserPreferences.mpvVideoOutput)
	val gpuContext by rememberPreference(userPreferences, UserPreferences.mpvGpuContext)
	val gpuApi by rememberPreference(userPreferences, UserPreferences.mpvGpuApi)
	val videoSync by rememberPreference(userPreferences, UserPreferences.mpvVideoSync)
	val frameDrop by rememberPreference(userPreferences, UserPreferences.mpvFrameDrop)
	val scaler by rememberPreference(userPreferences, UserPreferences.mpvScaler)
	val toneMapping by rememberPreference(userPreferences, UserPreferences.mpvToneMapping)
	val audioOutput by rememberPreference(userPreferences, UserPreferences.mpvAudioOutput)
	val audioChannels by rememberPreference(userPreferences, UserPreferences.mpvAudioChannels)
	val audioSpdif by rememberPreference(userPreferences, UserPreferences.mpvAudioSpdif)
	val replayGain by rememberPreference(userPreferences, UserPreferences.mpvReplayGain)
	val loopFilter by rememberPreference(userPreferences, UserPreferences.mpvLoopFilter)
	val subtitleAssOverride by rememberPreference(userPreferences, UserPreferences.mpvSubtitleAssOverride)
	var interpolation by rememberPreference(userPreferences, UserPreferences.mpvInterpolation)
	var deband by rememberPreference(userPreferences, UserPreferences.mpvDeband)
	var audioPitchCorrection by rememberPreference(userPreferences, UserPreferences.mpvAudioPitchCorrection)
	var subtitleUseMargins by rememberPreference(userPreferences, UserPreferences.mpvSubtitleUseMargins)
	var decoderThreads by rememberPreference(userPreferences, UserPreferences.mpvDecoderThreads)
	var optionOverrides by rememberPreference(userPreferences, UserPreferences.mpvOptionOverrides)
	val overrideCount = remember(optionOverrides) {
		parseLibMPVOptionOverrides(optionOverrides).values.keys.count { name -> !isLibMPVOptionManagedByJellyfin(name) }
	}

	LaunchedEffect(Unit) {
		// Remove stale raw entries that are now owned by a typed MPV or universal Jellyfin control.
		backendSettings.applyPreferences()
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback_advanced).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_mpv_options)) },
				captionContent = { Text(stringResource(R.string.preference_mpv_options_description)) },
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.preference_mpv_section_general)) }) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.DECODER, decoder) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.VIDEO_OUTPUT, videoOutput) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.GPU_CONTEXT, gpuContext) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.GPU_API, gpuApi) }

		item { ListSection(headingContent = { Text(stringResource(R.string.preference_mpv_section_video)) }) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.VIDEO_SYNC, videoSync) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.FRAME_DROP, frameDrop) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.SCALER, scaler) }
		item {
			LibMPVBooleanButton(
				headingRes = R.string.preference_mpv_interpolation,
				captionRes = R.string.preference_mpv_interpolation_description,
				checked = interpolation,
				defaultValue = false,
				onClick = {
					interpolation = !interpolation
					backendSettings.applyPreferences(clearOverrides = setOf("interpolation"))
				},
			)
		}
		item {
			LibMPVBooleanButton(
				headingRes = R.string.preference_mpv_deband,
				captionRes = R.string.preference_mpv_deband_description,
				checked = deband,
				defaultValue = false,
				onClick = {
					deband = !deband
					backendSettings.applyPreferences(clearOverrides = setOf("deband"))
				},
			)
		}
		item { LibMPVChoiceButton(LibMPVChoiceSetting.TONE_MAPPING, toneMapping) }

		item { ListSection(headingContent = { Text(stringResource(R.string.preference_mpv_section_audio)) }) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.AUDIO_OUTPUT, audioOutput) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.AUDIO_CHANNELS, audioChannels) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.AUDIO_SPDIF, audioSpdif) }
		item {
			LibMPVBooleanButton(
				headingRes = R.string.preference_mpv_audio_pitch_correction,
				captionRes = R.string.preference_mpv_audio_pitch_correction_description,
				checked = audioPitchCorrection,
				defaultValue = true,
				onClick = {
					audioPitchCorrection = !audioPitchCorrection
					backendSettings.applyPreferences(clearOverrides = setOf("audio-pitch-correction"))
				},
			)
		}
		item { LibMPVChoiceButton(LibMPVChoiceSetting.REPLAY_GAIN, replayGain) }

		item { ListSection(headingContent = { Text(stringResource(R.string.preference_mpv_section_decoder)) }) }
		item {
			val interactionSource = remember { MutableInteractionSource() }
			ListControl(
				headingContent = { Text(stringResource(R.string.preference_mpv_decoder_threads)) },
				captionContent = { Text(stringResource(R.string.preference_mpv_decoder_threads_description)) },
				interactionSource = interactionSource,
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					RangeControl(
						modifier = Modifier.height(4.dp).weight(1f),
						interactionSource = interactionSource,
						min = 0f,
						max = 32f,
						stepForward = 1f,
						value = decoderThreads.coerceIn(0, 32).toFloat(),
						onValueChange = {
							decoderThreads = it.roundToInt()
							backendSettings.applyPreferences(clearOverrides = setOf("vd-lavc-threads"))
						},
					)
					Spacer(Modifier.width(16.dp))
					Box(modifier = Modifier.sizeIn(minWidth = 88.dp), contentAlignment = Alignment.CenterEnd) {
						Text(if (decoderThreads == 0) stringResource(R.string.preference_mpv_value_auto) else decoderThreads.toString())
					}
				}
			}
		}
		item { LibMPVChoiceButton(LibMPVChoiceSetting.LOOP_FILTER, loopFilter) }

		item { ListSection(headingContent = { Text(stringResource(R.string.preference_mpv_section_subtitles)) }) }
		item { LibMPVChoiceButton(LibMPVChoiceSetting.SUBTITLE_ASS_OVERRIDE, subtitleAssOverride) }
		item {
			LibMPVBooleanButton(
				headingRes = R.string.preference_mpv_sub_use_margins,
				captionRes = R.string.preference_mpv_sub_use_margins_description,
				checked = subtitleUseMargins,
				defaultValue = true,
				onClick = {
					subtitleUseMargins = !subtitleUseMargins
					backendSettings.applyPreferences(clearOverrides = setOf("sub-use-margins"))
				},
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.preference_mpv_section_expert)) }) }
		item {
			ListButton(
				overlineContent = { Text(stringResource(R.string.preference_mpv_override_count, overrideCount)) },
				headingContent = { Text(stringResource(R.string.preference_mpv_all_options)) },
				captionContent = { Text(stringResource(R.string.preference_mpv_all_options_description)) },
				onClick = { router.push(LibMPVSettingsRoutes.PLAYBACK_MPV_ALL_OPTIONS) },
			)
		}
		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.preference_mpv_edit_overrides)) },
				captionContent = { Text(stringResource(R.string.preference_mpv_edit_overrides_description)) },
				onClick = {
					showLibMPVOverridesEditor(context, optionOverrides) { updated ->
						optionOverrides = updated
						backendSettings.applyPreferences()
					}
				},
			)
		}
		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.preference_mpv_reset)) },
				captionContent = { Text(stringResource(R.string.preference_mpv_reset_description)) },
				onClick = {
					AlertDialog.Builder(context)
						.setTitle(R.string.preference_mpv_reset)
						.setMessage(R.string.preference_mpv_reset_confirmation)
						.setPositiveButton(R.string.preference_mpv_reset) { _, _ ->
							userPreferences.resetLibMPVPreferences()
							backendSettings.applyPreferences()
						}
						.setNegativeButton(android.R.string.cancel, null)
						.show()
				},
			)
		}
	}
}

@Composable
private fun LibMPVChoiceButton(setting: LibMPVChoiceSetting, selected: LibMPVPreferenceOption) {
	val router = LocalRouter.current
	val default = setting.defaultOption()
	ListButton(
		overlineContent = { Text(stringResource(selected.nameRes)) },
		headingContent = { Text(stringResource(setting.titleRes)) },
		captionContent = {
			Text(
				"${stringResource(setting.descriptionRes)} " +
					stringResource(R.string.preference_mpv_default_value, stringResource(default.nameRes))
			)
		},
		onClick = {
			router.push(
				route = LibMPVSettingsRoutes.PLAYBACK_MPV_CHOICE,
				parameters = mapOf("setting" to setting.slug),
			)
		},
	)
}

@Composable
private fun LibMPVBooleanButton(
	headingRes: Int,
	captionRes: Int,
	checked: Boolean,
	defaultValue: Boolean,
	onClick: () -> Unit,
) {
	ListButton(
		headingContent = { Text(stringResource(headingRes)) },
		captionContent = {
			Text(
				"${stringResource(captionRes)} " + stringResource(
					R.string.preference_mpv_default_value,
					stringResource(if (defaultValue) R.string.preference_mpv_value_enabled else R.string.preference_mpv_value_disabled),
				)
			)
		},
		trailingContent = { Checkbox(checked = checked) },
		onClick = onClick,
	)
}

@Composable
fun SettingsPlaybackLibMPVChoiceScreen(setting: LibMPVChoiceSetting) {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val backendSettings = koinInject<LibMPVBackendSettings>()
	val selected = setting.selected(userPreferences)
	val default = setting.defaultOption()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.preference_mpv_options).uppercase()) },
				headingContent = { Text(stringResource(setting.titleRes)) },
				captionContent = {
					Text(
						"${stringResource(setting.descriptionRes)} " +
							stringResource(R.string.preference_mpv_default_value, stringResource(default.nameRes))
					)
				},
			)
		}

		items(setting.options()) { entry ->
			ListButton(
				overlineContent = if (entry == default) {
					{ Text(stringResource(R.string.preference_mpv_default_badge)) }
				} else null,
				headingContent = { Text(stringResource(entry.nameRes)) },
				captionContent = {
					val nativeValue = entry.mpvValue.ifEmpty { stringResource(R.string.preference_mpv_value_auto) }
					Text("${stringResource(entry.descriptionRes)} ($nativeValue)")
				},
				trailingContent = { RadioButton(checked = selected == entry) },
				onClick = {
					setting.select(userPreferences, entry)
					backendSettings.applyPreferences(clearOverrides = setting.optionNames)
					router.back()
				},
			)
		}
	}
}

@Composable
fun SettingsPlaybackLibMPVAllOptionsScreen() {
	val context = LocalContext.current
	val userPreferences = koinInject<UserPreferences>()
	val backendSettings = koinInject<LibMPVBackendSettings>()
	var optionOverrides by rememberPreference(userPreferences, UserPreferences.mpvOptionOverrides)
	var options by remember { mutableStateOf<List<LibMPVOptionInfo>?>(null) }
	var error by remember { mutableStateOf<Throwable?>(null) }
	var filter by remember { mutableStateOf("") }
	var loadRequest by remember { mutableStateOf(0) }
	val parsedOverrides = remember(optionOverrides) { parseLibMPVOptionOverrides(optionOverrides) }
	val overrides = remember(parsedOverrides) {
		parsedOverrides.values.filterKeys { name -> !isLibMPVOptionManagedByJellyfin(name) }
	}
	val filteredOptions = remember(options, filter) {
		val query = filter.trim()
		if (query.isEmpty()) options.orEmpty()
		else options.orEmpty().filter { option -> option.matchesFilter(query) }
	}

	LaunchedEffect(loadRequest, optionOverrides) {
		options = null
		error = null
		backendSettings.applyPreferences()
		runCatching {
			withContext(Dispatchers.Default) { backendSettings.getOptionCatalog() }
		}.onSuccess { catalog ->
			options = catalog
		}.onFailure { failure ->
			error = failure
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.preference_mpv_options).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_mpv_all_options)) },
				captionContent = {
					Text(
						when {
							error != null -> stringResource(R.string.preference_mpv_all_options_error)
							options == null -> stringResource(R.string.preference_mpv_all_options_loading)
							filter.isNotBlank() -> stringResource(
								R.string.preference_mpv_all_options_filtered,
								filteredOptions.size,
								options.orEmpty().size,
								filter.trim(),
							)
							else -> stringResource(R.string.preference_mpv_all_options_loaded, options.orEmpty().size)
						}
					)
				},
			)
		}

		if (error != null) {
			item {
				ListButton(
					headingContent = { Text(stringResource(R.string.preference_mpv_retry_catalog)) },
					captionContent = { Text(stringResource(R.string.preference_mpv_retry_catalog_description)) },
					onClick = { loadRequest++ },
				)
			}
		}

		item {
			ListButton(
				overlineContent = if (filter.isNotBlank()) {
					{ Text(stringResource(R.string.preference_mpv_filter_active, filter.trim())) }
				} else null,
				headingContent = { Text(stringResource(R.string.preference_mpv_filter_options)) },
				captionContent = { Text(stringResource(R.string.preference_mpv_filter_options_description)) },
				onClick = {
					showLibMPVOptionFilterEditor(context, filter) { updated -> filter = updated }
				},
			)
		}

		item {
			ListButton(
				overlineContent = { Text(stringResource(R.string.preference_mpv_override_count, overrides.size)) },
				headingContent = { Text(stringResource(R.string.preference_mpv_edit_overrides)) },
				captionContent = { Text(stringResource(R.string.preference_mpv_all_options_override_precedence)) },
				onClick = {
					showLibMPVOverridesEditor(context, optionOverrides) { updated ->
						optionOverrides = updated
						backendSettings.applyPreferences()
					}
				},
			)
		}

		items(filteredOptions, key = { option -> option.name }) { option ->
			val override = overrides[option.name]
			val valueSummary = buildLibMPVOptionSummary(option, override)
			ListButton(
				overlineContent = {
					Text(
						if (override != null) {
							val displayedOverride = if (override.isEmpty()) {
								stringResource(R.string.preference_mpv_empty_value)
							} else {
								override
							}
							stringResource(R.string.preference_mpv_override_active, displayedOverride)
						} else {
							option.type ?: stringResource(R.string.preference_mpv_option_type_unknown)
						}
					)
				},
				headingContent = { Text(option.name) },
				captionContent = { Text(valueSummary) },
				onClick = {
					showLibMPVOptionEditor(context, option, override) { value ->
						backendSettings.setOptionOverride(option.name, value)
						optionOverrides = userPreferences[UserPreferences.mpvOptionOverrides]
					}
				},
			)
		}
	}
}

private fun LibMPVOptionInfo.matchesFilter(query: String): Boolean =
	name.contains(query, ignoreCase = true) ||
		type?.contains(query, ignoreCase = true) == true ||
		currentValue?.contains(query, ignoreCase = true) == true ||
		defaultValue?.contains(query, ignoreCase = true) == true ||
		choices.any { choice -> choice.contains(query, ignoreCase = true) }

@Composable
private fun buildLibMPVOptionSummary(option: LibMPVOptionInfo, override: String?): String {
	val notReported = stringResource(R.string.preference_mpv_not_reported)
	val empty = stringResource(R.string.preference_mpv_empty_value)
	fun displayValue(value: String?) = when {
		value == null -> notReported
		value.isEmpty() -> empty
		else -> value
	}

	val parts = mutableListOf<String>()
	parts += stringResource(R.string.preference_mpv_option_default, displayValue(option.defaultValue))
	parts += stringResource(R.string.preference_mpv_option_current, displayValue(override ?: option.currentValue))
	if (option.choices.isNotEmpty()) {
		val choices = option.choices.take(8).joinToString(", ") + if (option.choices.size > 8) "…" else ""
		parts += stringResource(R.string.preference_mpv_option_choices, choices)
	}
	if (option.minimum != null || option.maximum != null) {
		parts += stringResource(
			R.string.preference_mpv_option_range,
			option.minimum ?: "−∞",
			option.maximum ?: "+∞",
		)
	}
	if (option.expectsFile) parts += stringResource(R.string.preference_mpv_option_expects_file)
	if (option.readOnly) parts += stringResource(R.string.preference_mpv_option_managed)
	return parts.joinToString(" • ")
}

private fun String?.mpvDisplayValue(context: Context): String = when {
	this == null -> context.getString(R.string.preference_mpv_not_reported)
	isEmpty() -> context.getString(R.string.preference_mpv_empty_value)
	else -> this
}

private fun showLibMPVOptionEditor(
	context: Context,
	option: LibMPVOptionInfo,
	override: String?,
	onSave: (String?) -> Unit,
) {
	val details = buildList {
		add(context.getString(R.string.preference_mpv_option_type, option.type.mpvDisplayValue(context)))
		add(context.getString(R.string.preference_mpv_option_default, option.defaultValue.mpvDisplayValue(context)))
		add(context.getString(R.string.preference_mpv_option_current, (override ?: option.currentValue).mpvDisplayValue(context)))
		if (option.minimum != null || option.maximum != null) {
			add(context.getString(R.string.preference_mpv_option_range, option.minimum ?: "−∞", option.maximum ?: "+∞"))
		}
		if (option.choices.isNotEmpty()) {
			add(context.getString(R.string.preference_mpv_option_choices, option.choices.joinToString(", ")))
		}
		if (option.expectsFile) add(context.getString(R.string.preference_mpv_option_expects_file))
		if (option.readOnly) add(context.getString(R.string.preference_mpv_option_managed_description))
	}.joinToString("\n")

	val builder = AlertDialog.Builder(context)
		.setTitle(option.name)
		.setMessage(details)
		.setNegativeButton(android.R.string.cancel, null)

	if (option.readOnly) {
		builder.show()
		return
	}

	val input = EditText(context).apply {
		setSingleLine(true)
		inputType = InputType.TYPE_CLASS_TEXT
		setText(override ?: option.currentValue ?: option.defaultValue.orEmpty())
		setSelection(text.length)
	}
	val dialog = builder
		.setView(input)
		.setPositiveButton(R.string.preference_mpv_save_override, null)
		.setNeutralButton(R.string.preference_mpv_use_default) { _, _ -> onSave(null) }
		.create()

	dialog.setOnShowListener {
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			val value = normalizeLibMPVOptionValue(option, input.text.toString())
			val validationError = validateLibMPVOptionValue(option, value)
			if (validationError != null) {
				input.error = mpvOptionValidationMessage(context, option, validationError)
				return@setOnClickListener
			}

			input.error = null
			onSave(value)
			dialog.dismiss()
		}
	}
	dialog.show()
}

private fun mpvOptionValidationMessage(
	context: Context,
	option: LibMPVOptionInfo,
	error: LibMPVOptionValueError,
): String = when (error) {
	LibMPVOptionValueError.INVALID_FLAG -> context.getString(R.string.preference_mpv_invalid_flag)
	LibMPVOptionValueError.INVALID_INTEGER -> context.getString(R.string.preference_mpv_invalid_integer)
	LibMPVOptionValueError.INVALID_NUMBER -> context.getString(R.string.preference_mpv_invalid_number)
	LibMPVOptionValueError.BELOW_MINIMUM -> context.getString(
		R.string.preference_mpv_below_minimum,
		option.minimum.mpvDisplayValue(context),
	)
	LibMPVOptionValueError.ABOVE_MAXIMUM -> context.getString(
		R.string.preference_mpv_above_maximum,
		option.maximum.mpvDisplayValue(context),
	)
	LibMPVOptionValueError.INVALID_CHOICE -> {
		val displayedChoices = option.choices.take(12).joinToString(", ") +
			if (option.choices.size > 12) "…" else ""
		context.getString(R.string.preference_mpv_invalid_choice, displayedChoices)
	}
}

private fun showLibMPVOptionFilterEditor(
	context: Context,
	currentValue: String,
	onApply: (String) -> Unit,
) {
	val input = EditText(context).apply {
		setSingleLine(true)
		inputType = InputType.TYPE_CLASS_TEXT
		setText(currentValue)
		setSelection(text.length)
	}
	AlertDialog.Builder(context)
		.setTitle(R.string.preference_mpv_filter_options)
		.setMessage(R.string.preference_mpv_filter_options_help)
		.setView(input)
		.setPositiveButton(R.string.preference_mpv_filter_apply) { _, _ -> onApply(input.text.toString().trim()) }
		.setNeutralButton(R.string.preference_mpv_filter_clear) { _, _ -> onApply("") }
		.setNegativeButton(android.R.string.cancel, null)
		.show()
}

private fun showLibMPVOverridesEditor(
	context: Context,
	currentValue: String,
	onSave: (String) -> Unit,
) {
	val input = EditText(context).apply {
		inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
		minLines = 8
		maxLines = 18
		setHorizontallyScrolling(false)
		setText(currentValue)
		setSelection(text.length)
	}
	val dialog = AlertDialog.Builder(context)
		.setTitle(R.string.preference_mpv_edit_overrides)
		.setMessage(R.string.preference_mpv_edit_overrides_help)
		.setView(input)
		.setPositiveButton(R.string.preference_mpv_save_override, null)
		.setNeutralButton(R.string.preference_mpv_clear_overrides) { _, _ -> onSave("") }
		.setNegativeButton(android.R.string.cancel, null)
		.create()

	dialog.setOnShowListener {
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			val parsed = parseLibMPVOptionOverrides(input.text.toString())
			if (parsed.invalidLines.isNotEmpty()) {
				input.error = context.getString(
					R.string.preference_mpv_invalid_override_lines,
					parsed.invalidLines.joinToString(", "),
				)
				return@setOnClickListener
			}

			val editable = parsed.values.filterKeys { name -> !isLibMPVOptionManagedByJellyfin(name) }
			input.error = null
			onSave(serializeLibMPVOptionOverrides(editable))
			dialog.dismiss()
		}
	}
	dialog.show()
}
