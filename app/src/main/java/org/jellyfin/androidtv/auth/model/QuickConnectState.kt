package org.jellyfin.androidtv.auth.model

import org.jellyfin.sdk.api.client.exception.ApiClientException

sealed class QuickConnectState

/**
 * State unknown until first poll completed.
 */
data object UnknownQuickConnectState : QuickConnectState()

/**
 * Server does not have QuickConnect enabled.
 */
data object UnavailableQuickConnectState : QuickConnectState()

/**
 * Quick Connect failed because of a connection or server error.
 */
data class ErrorQuickConnectState(val error: ApiClientException) : QuickConnectState()

/**
 * Connection is pending.
 */
data class PendingQuickConnectState(val code: String) : QuickConnectState()

/**
 * User connected.
 */
data object ConnectedQuickConnectState : QuickConnectState()
