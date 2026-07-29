package com.system.launcher.tools

/** In-memory gate for the sensitive profile UI. Process recreation is locked by default; it does not alter keepAlive policy. */
class PrivacySessionController {
    private var authorized = false

    @Synchronized
    fun authorize() { authorized = true }

    @Synchronized
    fun revoke() { authorized = false }

    @Synchronized
    fun isAuthorized(): Boolean = authorized
}