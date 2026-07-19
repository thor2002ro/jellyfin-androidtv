package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.LibVlcBackendSettings
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.libVlcAudioOutput
import org.jellyfin.androidtv.preference.libVlcAudioTimeStretch
import org.jellyfin.androidtv.preference.libVlcDav1dThreadFrames
import org.jellyfin.androidtv.preference.libVlcDeblocking
import org.jellyfin.androidtv.preference.libVlcDecoder
import org.jellyfin.androidtv.preference.libVlcFrameSkip
import org.jellyfin.androidtv.preference.libVlcReplayGain
import org.jellyfin.androidtv.preference.libVlcReplayGainDefault
import org.jellyfin.androidtv.preference.libVlcReplayGainMode
import org.jellyfin.androidtv.preference.libVlcReplayGainPeakProtection
import org.jellyfin.androidtv.preference.libVlcReplayGainPreamp
import org.jellyfin.androidtv.preference.libVlcVideoOutput
import org.jellyfin.androidtv.preference.constant.LibVlcAudioOutput
import org.jellyfin.androidtv.preference.constant.LibVlcDeblocking
import org.jellyfin.androidtv.preference.constant.LibVlcDecoder
import org.jellyfin.androidtv.preference.constant.LibVlcReplayGainMode
import org.jellyfin.androidtv.preference.constant.LibVlcVideoOutput
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
import org.koin.compose.koinInject
import java.text.DecimalFormat
import kotlin.math.roundToInt

@Composable
fun SettingsPlaybackLibVlcScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val backendSettings = koinInject<LibVlcBackendSettings>()
	var decoder by rememberPreference(userPreferences, UserPreferences.libVlcDecoder)
	var videoOutput by rememberPreference(userPreferences, UserPreferences.libVlcVideoOutput)
	var audioOutput by rememberPreference(userPreferences, UserPreferences.libVlcAudioOutput)
	var replayGain by rememberPreference(userPreferences, UserPreferences.libVlcReplayGain)
	var replayGainMode by rememberPreference(userPreferences, UserPreferences.libVlcReplayGainMode)
	var replayGainPreamp by rememberPreference(userPreferences, UserPreferences.libVlcReplayGainPreamp)
	var replayGainDefault by rememberPreference(userPreferences, UserPreferences.libVlcReplayGainDefault)
	var replayGainPeakProtection by rememberPreference(userPreferences, UserPreferences.libVlcReplayGainPeakProtection)
	var deblocking by rememberPreference(userPreferences, UserPreferences.libVlcDeblocking)
	var frameSkip by rememberPreference(userPreferences, UserPreferences.libVlcFrameSkip)
	var audioTimeStretch by rememberPreference(userPreferences, UserPreferences.libVlcAudioTimeStretch)
	var dav1dThreadFrames by rememberPreference(userPreferences, UserPreferences.libVlcDav1dThreadFrames)
	fun syncOptions(
		newDeblocking: LibVlcDeblocking = deblocking,
		newFrameSkip: Boolean = frameSkip,
		newAudioTimeStretch: Boolean = audioTimeStretch,
		newDav1dThreadFrames: Int = dav1dThreadFrames,
	) = backendSettings.setPlaybackOptions(
		deblocking = newDeblocking,
		frameSkip = newFrameSkip,
		audioTimeStretch = newAudioTimeStretch,
		dav1dThreadFrames = newDav1dThreadFrames,
	)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback_advanced).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_options)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_options_description)) },
			)
		}

		item {
			val interactionSource = remember { MutableInteractionSource() }

			ListControl(
				headingContent = { Text(stringResource(R.string.preference_libvlc_dav1d_thread_frames)) },
				interactionSource = interactionSource,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
				) {
					RangeControl(
						modifier = Modifier
							.height(4.dp)
							.weight(1f),
						interactionSource = interactionSource,
						min = 0f,
						max = 16f,
						stepForward = 1f,
						value = dav1dThreadFrames.toFloat(),
						onValueChange = {
							val value = it.roundToInt()
							dav1dThreadFrames = value
							syncOptions(newDav1dThreadFrames = value)
						},
					)

					Spacer(Modifier.width(16.dp))

					Box(
						modifier = Modifier.sizeIn(minWidth = 88.dp),
						contentAlignment = Alignment.CenterEnd,
					) {
						Text(
							if (dav1dThreadFrames == 0) {
								stringResource(R.string.preference_libvlc_value_auto)
							} else {
								dav1dThreadFrames.toString()
							},
						)
					}
				}
			}
		}

		item {
			ListButton(
				overlineContent = { Text(stringResource(videoOutput.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_video_output)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_video_output_description)) },
				onClick = { router.push(LibVlcSettingsRoutes.PLAYBACK_LIBVLC_VIDEO_OUTPUT) },
			)
		}

		item {
			ListButton(
				overlineContent = { Text(stringResource(audioOutput.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_audio_output)) },
				captionContent = { Text(stringResource(audioOutput.descriptionRes)) },
				onClick = { router.push(LibVlcSettingsRoutes.PLAYBACK_LIBVLC_AUDIO_OUTPUT) },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.preference_libvlc_replay_gain)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_replay_gain_description)) },
				trailingContent = { Checkbox(checked = replayGain) },
				onClick = { replayGain = !replayGain },
			)
		}

		item {
			ListButton(
				overlineContent = { Text(stringResource(replayGainMode.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_replay_gain_mode)) },
				captionContent = { Text("${stringResource(replayGainMode.descriptionRes)} ${stringResource(R.string.preference_libvlc_restart_required)}") },
				onClick = { router.push(LibVlcSettingsRoutes.PLAYBACK_LIBVLC_REPLAY_GAIN_MODE) },
			)
		}

		item {
			LibVlcDbRangeControl(
				headingRes = R.string.preference_libvlc_replay_gain_preamp,
				captionRes = R.string.preference_libvlc_restart_required,
				value = replayGainPreamp,
				onValueChange = { replayGainPreamp = it },
			)
		}

		item {
			LibVlcDbRangeControl(
				headingRes = R.string.preference_libvlc_replay_gain_default,
				captionRes = R.string.preference_libvlc_restart_required,
				value = replayGainDefault,
				onValueChange = { replayGainDefault = it },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.preference_libvlc_replay_gain_peak_protection)) },
				captionContent = {
					Text(
						"${stringResource(R.string.preference_libvlc_replay_gain_peak_protection_description)} " +
							stringResource(R.string.preference_libvlc_restart_required)
					)
				},
				trailingContent = { Checkbox(checked = replayGainPeakProtection) },
				onClick = { replayGainPeakProtection = !replayGainPeakProtection },
			)
		}

		item {
			ListButton(
				overlineContent = { Text(stringResource(decoder.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_decoder)) },
				captionContent = { Text(stringResource(decoder.descriptionRes)) },
				onClick = { router.push(LibVlcSettingsRoutes.PLAYBACK_LIBVLC_DECODER) },
			)
		}

		item {
			ListButton(
				overlineContent = { Text(stringResource(deblocking.nameRes)) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_deblocking)) },
				captionContent = { Text(stringResource(deblocking.descriptionRes)) },
				onClick = { router.push(LibVlcSettingsRoutes.PLAYBACK_LIBVLC_DEBLOCKING) },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.preference_libvlc_frame_skip)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_frame_skip_description)) },
				trailingContent = { Checkbox(checked = frameSkip) },
				onClick = {
					val enabled = !frameSkip
					frameSkip = enabled
					syncOptions(newFrameSkip = enabled)
				},
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.preference_libvlc_audio_time_stretch)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_audio_time_stretch_description)) },
				trailingContent = { Checkbox(checked = audioTimeStretch) },
				onClick = {
					val enabled = !audioTimeStretch
					audioTimeStretch = enabled
					syncOptions(newAudioTimeStretch = enabled)
				},
			)
		}
	}
}

@Composable
fun SettingsPlaybackLibVlcDecoderScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val backendSettings = koinInject<LibVlcBackendSettings>()
	val decoder = userPreferences[UserPreferences.libVlcDecoder]

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.preference_libvlc_options).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_decoder)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_decoder_description)) },
			)
		}

		items(LibVlcDecoder.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				captionContent = { Text(stringResource(entry.descriptionRes)) },
				trailingContent = { RadioButton(checked = decoder == entry) },
				onClick = {
					userPreferences[UserPreferences.libVlcDecoder] = entry
					backendSettings.setVideoDecoder(entry)
					router.back()
				},
			)
		}
	}
}

@Composable
private fun LibVlcDbRangeControl(
	headingRes: Int,
	captionRes: Int? = null,
	value: Float,
	onValueChange: (Float) -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val formatter = remember { DecimalFormat("0.#") }

	ListControl(
		headingContent = { Text(stringResource(headingRes)) },
		captionContent = captionRes?.let { { Text(stringResource(it)) } },
		interactionSource = interactionSource,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
		) {
			RangeControl(
				modifier = Modifier
					.height(4.dp)
					.weight(1f),
				interactionSource = interactionSource,
				min = -20f,
				max = 20f,
				stepForward = 0.5f,
				value = value.coerceIn(-20f, 20f),
				onValueChange = onValueChange,
			)

			Spacer(Modifier.width(16.dp))

			Box(
				modifier = Modifier.sizeIn(minWidth = 88.dp),
				contentAlignment = Alignment.CenterEnd,
			) {
				Text("${formatter.format(value)} dB")
			}
		}
	}
}

@Composable
fun SettingsPlaybackLibVlcVideoOutputScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val videoOutput = userPreferences[UserPreferences.libVlcVideoOutput]

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.preference_libvlc_options).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_video_output)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_video_output_description)) },
			)
		}

		items(LibVlcVideoOutput.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				captionContent = { Text(stringResource(entry.descriptionRes)) },
				trailingContent = { RadioButton(checked = videoOutput == entry) },
				onClick = {
					userPreferences[UserPreferences.libVlcVideoOutput] = entry
					router.back()
				},
			)
		}
	}
}

@Composable
fun SettingsPlaybackLibVlcAudioOutputScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val audioOutput = userPreferences[UserPreferences.libVlcAudioOutput]

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.preference_libvlc_options).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_audio_output)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_audio_output_description)) },
			)
		}

		items(LibVlcAudioOutput.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				captionContent = { Text(stringResource(entry.descriptionRes)) },
				trailingContent = { RadioButton(checked = audioOutput == entry) },
				onClick = {
					userPreferences[UserPreferences.libVlcAudioOutput] = entry
					router.back()
				},
			)
		}
	}
}

@Composable
fun SettingsPlaybackLibVlcReplayGainModeScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val replayGainMode = userPreferences[UserPreferences.libVlcReplayGainMode]

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.preference_libvlc_options).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_replay_gain_mode)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_replay_gain_mode_description)) },
			)
		}

		items(LibVlcReplayGainMode.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				captionContent = { Text(stringResource(entry.descriptionRes)) },
				trailingContent = { RadioButton(checked = replayGainMode == entry) },
				onClick = {
					userPreferences[UserPreferences.libVlcReplayGainMode] = entry
					router.back()
				},
			)
		}
	}
}

@Composable
fun SettingsPlaybackLibVlcDeblockingScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val backendSettings = koinInject<LibVlcBackendSettings>()
	val deblocking = userPreferences[UserPreferences.libVlcDeblocking]

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.preference_libvlc_options).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_libvlc_deblocking)) },
				captionContent = { Text(stringResource(R.string.preference_libvlc_deblocking_description)) },
			)
		}

		items(LibVlcDeblocking.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				captionContent = { Text(stringResource(entry.descriptionRes)) },
				trailingContent = { RadioButton(checked = deblocking == entry) },
				onClick = {
					userPreferences[UserPreferences.libVlcDeblocking] = entry
					backendSettings.setPlaybackOptions(deblocking = entry)
					router.back()
				},
			)
		}
	}
}
