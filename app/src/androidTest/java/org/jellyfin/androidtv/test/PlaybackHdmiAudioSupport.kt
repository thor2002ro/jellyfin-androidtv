package org.jellyfin.androidtv.test

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager

data class PlaybackHdmiAudioSupport(
	val outputPresent: Boolean,
	val codecs: Set<String>,
	val detail: String,
)

fun detectHdmiAudioSupport(context: Context): PlaybackHdmiAudioSupport {
	val manager = context.getSystemService(AudioManager::class.java)
	val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { device ->
		device.type == AudioDeviceInfo.TYPE_HDMI || device.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
			(android.os.Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_HDMI_EARC)
	}
	val encodings = outputs.flatMap { it.encodings.asIterable() }.toSet()
	val codecs = buildSet {
		if (AudioFormat.ENCODING_AC3 in encodings) add("ac3")
		if (AudioFormat.ENCODING_E_AC3 in encodings || AudioFormat.ENCODING_E_AC3_JOC in encodings) add("eac3")
		if (AudioFormat.ENCODING_DTS in encodings) add("dts")
		if (AudioFormat.ENCODING_DTS_HD in encodings) add("dtshd")
		if (AudioFormat.ENCODING_DOLBY_TRUEHD in encodings) add("truehd")
		if (android.os.Build.VERSION.SDK_INT >= 28 && AudioFormat.ENCODING_AC4 in encodings) add("ac4")
	}
	return PlaybackHdmiAudioSupport(
		outputPresent = outputs.isNotEmpty(),
		codecs = codecs,
		detail = "outputs=${outputs.map { it.productName }} encodings=${encodings.sorted()}",
	)
}
