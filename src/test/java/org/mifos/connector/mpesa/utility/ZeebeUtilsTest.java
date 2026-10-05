package org.mifos.connector.mpesa.utility;

import org.apache.camel.util.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZeebeUtilsTest {

    @Test
    void getNextTimer_shouldDoubleToNextPowerOfTwo() {
        assertEquals("PT4S", ZeebeUtils.getNextTimer("PT2S"));
        assertEquals("PT32S", ZeebeUtils.getNextTimer("PT30S"));
        assertEquals("PT64S", ZeebeUtils.getNextTimer("PT45S"));
        assertEquals("PT2S", ZeebeUtils.getNextTimer("PT1S"));
    }

    @Test
    void getTransferResponseCreateJson_shouldContainCompletedTimestamp() {
        JsonObject json = ZeebeUtils.getTransferResponseCreateJson();

        assertTrue(Long.parseLong((String) json.get("completedTimestamp")) > 0);
    }

    @Test
    void sleep_shouldReturnForZeroSeconds() {
        ZeebeUtils.sleep(0);
    }

    @Test
    void sleep_whenInterrupted_shouldThrowIllegalState() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> ZeebeUtils.sleep(1));
        } finally {
            Thread.interrupted();
        }
    }
}
