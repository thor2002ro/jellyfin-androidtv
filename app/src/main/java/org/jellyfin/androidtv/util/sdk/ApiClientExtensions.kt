package org.jellyfin.androidtv.util.sdk

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.delete


/**
 * Check if the [baseUrl] and [accessToken] are not null.
 */
val ApiClient.isUsable
	get() = baseUrl != null && accessToken != null

fun ApiClient.clientLogUrl() = createUrl("/ClientLog/Document")

suspend fun ApiClient.stopEncodingProcess(
	deviceId: String,
	playSessionId: String,
): Response<Unit> = delete(
	pathTemplate = "/Videos/ActiveEncodings",
	queryParameters = mapOf(
		"deviceId" to deviceId,
		"playSessionId" to playSessionId,
	),
)
