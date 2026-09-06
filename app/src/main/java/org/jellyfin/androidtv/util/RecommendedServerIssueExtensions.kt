package org.jellyfin.androidtv.util

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.sdk.discovery.RecommendedServerIssue

/**
 * Create a localized string with the message to show an end-user of the app.
 */
fun RecommendedServerIssue.getFriendlyMessage(context: Context): String = when (this) {
	// Connection issues
	is RecommendedServerIssue.SecureConnectionFailed -> sslException.getConnectionErrorReason(context)
	is RecommendedServerIssue.SlowResponse -> context.getString(R.string.server_issue_timeout)
	is RecommendedServerIssue.ServerUnreachable -> throwable.getConnectionErrorReason(context)

	// Response issues
	is RecommendedServerIssue.MissingSystemInfo -> throwable?.getConnectionErrorReason(context)
		?: context.getString(R.string.server_issue_invalid_response)
	is RecommendedServerIssue.InvalidProductName -> context.getString(R.string.server_issue_invalid_product)
	RecommendedServerIssue.MissingVersion -> context.getString(R.string.server_issue_missing_version)

	// Versioning issues
	is RecommendedServerIssue.UnsupportedServerVersion -> context.getString(
		R.string.server_issue_unsupported_version,
		version.toString(),
	)

	is RecommendedServerIssue.OutdatedServerVersion -> context.getString(
		R.string.server_issue_outdated_version,
		version.toString(),
		ServerRepository.recommendedServerVersion.toString()
	)
}

/**
 * Get the summary for a collection of issues. This is the most significant issue in the collection
 * passed trough [RecommendedServerIssue.getFriendlyMessage].
 */
@Suppress("MagicNumber")
fun Collection<RecommendedServerIssue>.getSummary(context: Context): String = maxByOrNull {
	// Assign a score to each issue type so we can return the most important one. Higher number
	// means more important.
	when (it) {
		is RecommendedServerIssue.MissingSystemInfo -> 7
		is RecommendedServerIssue.UnsupportedServerVersion -> 6
		is RecommendedServerIssue.OutdatedServerVersion -> 5
		is RecommendedServerIssue.SecureConnectionFailed -> 4
		is RecommendedServerIssue.SlowResponse -> 3
		is RecommendedServerIssue.ServerUnreachable -> 2
		RecommendedServerIssue.MissingVersion -> 1
		is RecommendedServerIssue.InvalidProductName -> 0
	}
}?.getFriendlyMessage(context) ?: context.getString(R.string.server_issue_unable_to_connect)

fun RecommendedServerIssue.getLogSummary(): String = when (this) {
	is RecommendedServerIssue.SecureConnectionFailed -> "secure connection failed: ${sslException.getConnectionErrorLogReason()}"
	is RecommendedServerIssue.SlowResponse -> "slow response: ${responseTime}ms"
	is RecommendedServerIssue.ServerUnreachable -> "server unreachable: ${throwable.getConnectionErrorLogReason()}"
	is RecommendedServerIssue.MissingSystemInfo -> "missing system info: ${throwable?.getConnectionErrorLogReason() ?: "no response"}"
	is RecommendedServerIssue.InvalidProductName -> "invalid product name: $productName"
	RecommendedServerIssue.MissingVersion -> "missing server version"
	is RecommendedServerIssue.UnsupportedServerVersion -> "unsupported server version: $version"
	is RecommendedServerIssue.OutdatedServerVersion -> "outdated server version: $version"
}
