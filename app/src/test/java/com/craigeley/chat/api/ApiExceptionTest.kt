package com.craigeley.chat.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiExceptionTest {
    @Test fun onlyExplicitLookupFailuresAllowAnotherRoom() {
        assertTrue(ApiException(500, "Can't get chat id: -1728").isRoomLookupError)
        assertFalse(ApiException(500, "Message send timed out").isRoomLookupError)
        assertFalse(ApiException(401, "Can't get chat id").isRoomLookupError)
        assertFalse(ApiException(500, "Can't use the send message script to text a group chat!").isRoomLookupError)
    }
}
