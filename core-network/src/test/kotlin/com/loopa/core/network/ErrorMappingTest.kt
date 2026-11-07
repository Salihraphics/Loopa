package com.loopa.core.network

import com.loopa.core.game.LoopaError
import io.github.jan.supabase.exceptions.HttpRequestException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ErrorMappingTest {
    @Test fun anonymousDisabledIsNotReportedAsNetwork() {
        // Shape observed against a real project with anonymous sign-ins switched off.
        val e = HttpRequestException(
            "failed with message: {\"code\":422,\"error_code\":\"anonymous_provider_disabled\",\"msg\":\"Anonymous sign-ins are disabled\"}",
            io.ktor.client.request.HttpRequestBuilder(),
        )
        assertEquals(LoopaError.ANONYMOUS_DISABLED, LoopaBackend.classify(e))
    }

    @Test fun databaseCodesWin() {
        assertEquals(LoopaError.ROOM_FULL, LoopaBackend.classify(RuntimeException("x", RuntimeException("LOOPA:ROOM_FULL"))))
    }

    @Test fun realTransportErrorsAreNetwork() {
        assertEquals(LoopaError.NETWORK, LoopaBackend.classify(IOException("Unable to resolve host")))
    }
}
