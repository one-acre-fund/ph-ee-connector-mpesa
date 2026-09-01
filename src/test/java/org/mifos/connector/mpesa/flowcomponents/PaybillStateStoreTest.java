package org.mifos.connector.mpesa.flowcomponents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaybillStateStoreTest {

    private static final String KEY_PREFIX = "test-prefix";
    private static final String MPESA_TXN_ID = "txn-123";
    private static final long RECONCILED_TTL_SECONDS = 900;
    private static final long WORKFLOW_TTL_SECONDS = 172800;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PaybillStateStore paybillStateStore;

    @BeforeEach
    void setUp() {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        RedisStoreProperties.Ttl ttl = new RedisStoreProperties.Ttl();
        ttl.setPaybillReconciledSeconds(RECONCILED_TTL_SECONDS);
        ttl.setPaybillWorkflowSeconds(WORKFLOW_TTL_SECONDS);
        properties.setTtl(ttl);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        paybillStateStore = new PaybillStateStore(redisTemplate, properties);
    }

    @Test
    void putReconciled_shouldStoreBooleanAsStringWithTtl() {
        paybillStateStore.putReconciled(MPESA_TXN_ID, true);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq(reconciledKey(MPESA_TXN_ID)),
                org.mockito.ArgumentMatchers.eq("true"),
                ttlCaptor.capture()
        );
        assertEquals(RECONCILED_TTL_SECONDS, ttlCaptor.getValue().getSeconds());
    }

    @Test
    void getReconciled_shouldReturnTrueWhenStoredAsTrue() {
        when(valueOperations.get(reconciledKey(MPESA_TXN_ID))).thenReturn("true");

        assertTrue(paybillStateStore.getReconciled(MPESA_TXN_ID));
    }

    @Test
    void getReconciled_shouldReturnFalseWhenStoredAsFalse() {
        when(valueOperations.get(reconciledKey(MPESA_TXN_ID))).thenReturn("false");

        assertFalse(paybillStateStore.getReconciled(MPESA_TXN_ID));
    }

    @Test
    void getReconciled_shouldReturnNullWhenMissing() {
        when(valueOperations.get(reconciledKey(MPESA_TXN_ID))).thenReturn(null);

        assertNull(paybillStateStore.getReconciled(MPESA_TXN_ID));
    }

    @Test
    void removeReconciled_shouldDeleteKey() {
        paybillStateStore.removeReconciled(MPESA_TXN_ID);

        verify(redisTemplate).delete(reconciledKey(MPESA_TXN_ID));
    }

    @Test
    void putWorkflowInstance_shouldStoreWorkflowKeyWithTtl() {
        paybillStateStore.putWorkflowInstance(MPESA_TXN_ID, "workflow-456");

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq(workflowKey(MPESA_TXN_ID)),
                org.mockito.ArgumentMatchers.eq("workflow-456"),
                ttlCaptor.capture()
        );
        assertEquals(WORKFLOW_TTL_SECONDS, ttlCaptor.getValue().getSeconds());
    }

    @Test
    void getWorkflowInstance_shouldReturnStoredValue() {
        when(valueOperations.get(workflowKey(MPESA_TXN_ID))).thenReturn("workflow-456");

        assertEquals("workflow-456", paybillStateStore.getWorkflowInstance(MPESA_TXN_ID));
    }

    @Test
    void removeWorkflowInstance_shouldDeleteKey() {
        paybillStateStore.removeWorkflowInstance(MPESA_TXN_ID);

        verify(redisTemplate).delete(workflowKey(MPESA_TXN_ID));
    }

    private String reconciledKey(String mpesaTxnId) {
        return KEY_PREFIX + ":paybill:reconciled:" + mpesaTxnId;
    }

    private String workflowKey(String mpesaTxnId) {
        return KEY_PREFIX + ":paybill:workflow:" + mpesaTxnId;
    }
}
