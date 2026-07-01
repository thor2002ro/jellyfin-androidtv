package org.jellyfin.androidtv.util

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

fun Throwable.getConnectionErrorMessage(context: Context): String = context.getString(
	R.string.server_connection_failed_with_reason,
	getConnectionErrorReason(context)
)

fun Throwable.getConnectionErrorReason(context: Context): String {
	findCause<InvalidStatusException>()?.let { return context.getString(R.string.server_issue_http_status, it.status) }
	findCause<InvalidContentException>()?.let {
		return it.deepestMessage() ?: context.getString(R.string.server_issue_invalid_response)
	}

	return when (diagnoseConnectionError()) {
		ConnectionErrorDiagnosis.CERTIFICATE_NOT_TRUSTED -> context.getString(R.string.server_issue_tls_certificate_untrusted)
		ConnectionErrorDiagnosis.CERTIFICATE_DATE_INVALID -> context.getString(R.string.server_issue_tls_certificate_date_invalid)
		ConnectionErrorDiagnosis.HOSTNAME_MISMATCH -> context.getString(R.string.server_issue_tls_hostname_mismatch)
		ConnectionErrorDiagnosis.SECURE_CONNECTION_FAILED -> context.getString(R.string.server_issue_tls_connection_failed)
		ConnectionErrorDiagnosis.DNS_FAILED -> context.getString(R.string.server_issue_dns_failed)
		ConnectionErrorDiagnosis.CONNECTION_REFUSED -> context.getString(R.string.server_issue_connection_refused)
		ConnectionErrorDiagnosis.NETWORK_UNREACHABLE -> context.getString(R.string.server_issue_network_unreachable)
		ConnectionErrorDiagnosis.TIMEOUT -> context.getString(R.string.server_issue_timeout)
		null -> deepestMessage() ?: message.cleanMessage() ?: context.getString(R.string.server_issue_unable_to_connect)
	}
}

fun Throwable.getConnectionErrorLogReason(): String {
	findCause<InvalidStatusException>()?.let { return toLogReason("http status ${it.status}") }
	findCause<InvalidContentException>()?.let { return toLogReason("invalid server response") }

	return toLogReason(diagnoseConnectionError()?.logLabel ?: "connection failed")
}

private enum class ConnectionErrorDiagnosis(val logLabel: String) {
	CERTIFICATE_NOT_TRUSTED("tls certificate not trusted by this device"),
	CERTIFICATE_DATE_INVALID("tls certificate expired or not valid yet"),
	HOSTNAME_MISMATCH("tls hostname mismatch"),
	SECURE_CONNECTION_FAILED("tls handshake failed"),
	DNS_FAILED("dns lookup failed"),
	CONNECTION_REFUSED("connection refused"),
	NETWORK_UNREACHABLE("network unreachable"),
	TIMEOUT("connection timed out"),
}

private fun Throwable.diagnoseConnectionError(): ConnectionErrorDiagnosis? = when {
	findCause<CertificateExpiredException>() != null ||
		findCause<CertificateNotYetValidException>() != null ||
		matchesCauseText("certificate has expired", "certificate expired", "not yet valid", "NotAfter", "NotBefore") ->
		ConnectionErrorDiagnosis.CERTIFICATE_DATE_INVALID

	findCause<CertPathValidatorException>() != null ||
		matchesCauseText(
			"Trust anchor for certification path not found",
			"unable to find valid certification path",
			"PKIX path building failed",
			"self-signed certificate",
			"certificate chain",
		) ->
		ConnectionErrorDiagnosis.CERTIFICATE_NOT_TRUSTED

	findCause<UnknownHostException>() != null ->
		ConnectionErrorDiagnosis.DNS_FAILED

	findCause<SSLPeerUnverifiedException>() != null ||
		matchesCauseText("Hostname ", "No subject alternative", "subjectAltNames", "not verified", "doesn't match") ->
		ConnectionErrorDiagnosis.HOSTNAME_MISMATCH

	findCause<SecureConnectionException>() != null ||
		findCause<SSLHandshakeException>() != null ->
		ConnectionErrorDiagnosis.SECURE_CONNECTION_FAILED

	findCause<TimeoutException>() != null ||
		findCause<SocketTimeoutException>() != null ->
		ConnectionErrorDiagnosis.TIMEOUT

	findCause<ConnectException>() != null &&
		matchesCauseText("Connection refused", "ECONNREFUSED") ->
		ConnectionErrorDiagnosis.CONNECTION_REFUSED

	findCause<NoRouteToHostException>() != null ||
		matchesCauseText("Network is unreachable", "No route to host", "ENETUNREACH") ->
		ConnectionErrorDiagnosis.NETWORK_UNREACHABLE

	else -> null
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? = causeChain()
	.filterIsInstance<T>()
	.firstOrNull()

private fun Throwable.deepestMessage(): String? = generateSequence(this) { it.cause }
	.mapNotNull { it.message.cleanMessage() }
	.lastOrNull()

private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

private fun Throwable.matchesCauseText(vararg values: String): Boolean = causeChain().any { throwable ->
	val text = buildString {
		append(throwable.javaClass.name)
		throwable.message.cleanMessage()?.let {
			append(": ")
			append(it)
		}
	}

	values.any { value -> text.contains(value, ignoreCase = true) }
}

private fun Throwable.toLogReason(label: String): String = buildString {
	append(label)
	deepestMessage()?.let {
		append(": ")
		append(it)
	}

	val chain = causeChainSummary()
	if (chain != null) {
		append(" [")
		append(chain)
		append("]")
	}
}

private fun Throwable.causeChainSummary(): String? = causeChain()
	.joinToString(" -> ") { throwable ->
		buildString {
			append(throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name })
			throwable.message.cleanMessage()?.let {
				append(": ")
				append(it)
			}
		}
	}
	.cleanMessage()
	?.let { if (it.length > MAX_LOG_REASON_LENGTH) "${it.take(MAX_LOG_REASON_LENGTH - 3)}..." else it }

private fun String?.cleanMessage(): String? = this
	?.trim()
	?.takeIf { it.isNotBlank() }

private const val MAX_LOG_REASON_LENGTH = 800
