package io.github.mgdx.sceau.ui.reading

import io.github.mgdx.sceau.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingErrorsTest {
    @Test
    fun `known codes map to their message`() {
        assertEquals(R.string.reading_error_access_denied, ReadingErrors.messageFor("ACCESS_DENIED"))
        assertEquals(R.string.reading_error_connection_lost, ReadingErrors.messageFor("CONNECTION_LOST"))
        assertEquals(R.string.reading_error_not_icao, ReadingErrors.messageFor("NOT_ICAO"))
        assertEquals(R.string.reading_error_timeout, ReadingErrors.messageFor("TIMEOUT"))
        assertEquals(R.string.reading_error_can_without_pace, ReadingErrors.messageFor("CAN_WITHOUT_PACE"))
        assertFalse(ReadingErrors.showsCode("ACCESS_DENIED"))
    }

    @Test
    fun `unknown codes are unexpected and show the code`() {
        assertEquals(R.string.reading_error_unexpected, ReadingErrors.messageFor("UNEXPECTED-IOException"))
        assertTrue(ReadingErrors.showsCode("UNEXPECTED-IOException"))
        assertTrue(ReadingErrors.showsCode("SOMETHING_NEW"))
    }
}
