package com.loopa.core.data

import com.loopa.core.game.LoopaError
import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthCallbackTest {
    @Test fun parsesPkceCode() {
        assertEquals("abc-123", OAuthCallback.parse("loopa://auth-callback?code=abc-123")["code"])
    }

    @Test fun parsesErrorsFromQueryAndFragment() {
        val q = OAuthCallback.parse("loopa://auth-callback?error=server_error&error_code=identity_already_exists&error_description=Identity+is+already+linked+to+another+user")
        assertEquals("identity_already_exists", q["error_code"])
        assertEquals("Identity is already linked to another user", q["error_description"])
        val f = OAuthCallback.parse("loopa://auth-callback#error=access_denied&error_description=User%20cancelled")
        assertEquals("access_denied", f["error"])
    }

    @Test fun mapsServerErrors() {
        assertEquals(LoopaError.IDENTITY_ALREADY_LINKED, LoopaError.fromOAuthError("identity_already_exists", null))
        assertEquals(LoopaError.MANUAL_LINKING_DISABLED, LoopaError.fromOAuthError("manual_linking_disabled", null))
        assertEquals(LoopaError.GOOGLE_NOT_CONFIGURED, LoopaError.fromOAuthError("validation_failed", "Unsupported provider: provider is not enabled"))
        assertEquals(LoopaError.AUTH_CANCELLED, LoopaError.fromOAuthError("access_denied", null))
        assertEquals(LoopaError.OAUTH_FAILED, LoopaError.fromOAuthError("server_error", "boom"))
    }
}
