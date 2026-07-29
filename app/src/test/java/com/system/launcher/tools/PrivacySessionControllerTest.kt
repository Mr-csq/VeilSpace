package com.system.launcher.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySessionControllerTest {
    @Test fun `new session is locked`() = assertFalse(PrivacySessionController().isAuthorized())

    @Test fun `triple tap authorization unlocks this session`() {
        assertTrue(PrivacySessionController().apply { authorize() }.isAuthorized())
    }

    @Test fun `launching a target revokes authorization`() {
        assertFalse(PrivacySessionController().apply { authorize(); revoke() }.isAuthorized())
    }

    @Test fun `screen off revokes authorization`() {
        assertFalse(PrivacySessionController().apply { authorize(); revoke() }.isAuthorized())
    }

    @Test fun `process recreation is equivalent to a locked session`() {
        assertFalse(PrivacySessionController().isAuthorized())
    }
}