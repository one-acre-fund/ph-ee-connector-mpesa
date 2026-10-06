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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaybillStateStoreTest {

    private static final String KEY_PREFIX = "test-prefix";
    private static final String MPESA_TXN_ID = "txn-123";
    private static final String OTHER_TXN_ID = "txn-999";
    private static final long RECONCILED_TTL_SECONDS = 900;
    private static final long WORKFLOW_TTL_SECONDS = 172800;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void redisPutReconciled_shouldStoreBooleanAsStringWithTtl() {
        redisStore().putReconciled(MPESA_TXN_ID, true);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq(reconciledKey(MPESA_TXN_ID)),
                org.mockito.ArgumentMatchers.eq("true"),
                ttlCaptor.capture()
        );
        assertEquals(RECONCILED_TTL_SECONDS, ttlCaptor.getValue().getSeconds());
    }

    @Test
    void redisGetReconciled_shouldReturnTrueWhenStoredAsTrue() {
        when(valueOperations.get(reconciledKey(MPESA_TXN_ID))).thenReturn("true");

        assertTrue(redisStore().getReconciled(MPESA_TXN_ID));
    }

    @Test
    void redisGetReconciled_shouldReturnFalseWhenStoredAsFalse() {
        when(valueOperations.get(reconciledKey(MPESA_TXN_ID))).thenReturn("false");

        assertFalse(redisStore().getReconciled(MPESA_TXN_ID));
    }

    @Test
    void redisGetReconciled_shouldReturnNullWhenMissing() {
        when(valueOperations.get(reconciledKey(MPESA_TXN_ID))).thenReturn(null);

        assertNull(redisStore().getReconciled(MPESA_TXN_ID));
    }

    @Test
    void redisRemoveReconciled_shouldDeleteKey() {
        redisStore().removeReconciled(MPESA_TXN_ID);

        verify(redisTemplate).delete(reconciledKey(MPESA_TXN_ID));
    }

    @Test
    void redisPutWorkflowInstance_shouldStoreWorkflowKeyWithTtl() {
        redisStore().putWorkflowInstance(MPESA_TXN_ID, "workflow-456");

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq(workflowKey(MPESA_TXN_ID)),
                org.mockito.ArgumentMatchers.eq("workflow-456"),
                ttlCaptor.capture()
        );
        assertEquals(WORKFLOW_TTL_SECONDS, ttlCaptor.getValue().getSeconds());
    }

    @Test
    void redisGetWorkflowInstance_shouldReturnStoredValue() {
        when(valueOperations.get(workflowKey(MPESA_TXN_ID))).thenReturn("workflow-456");

        assertEquals("workflow-456", redisStore().getWorkflowInstance(MPESA_TXN_ID));
    }

    @Test
    void redisRemoveWorkflowInstance_shouldDeleteKey() {
        redisStore().removeWorkflowInstance(MPESA_TXN_ID);

        verify(redisTemplate).delete(workflowKey(MPESA_TXN_ID));
    }

    @Test
    void memoryStore_shouldPutGetAndRemoveWithoutRedis() {
        InMemoryPaybillStateStore store = memoryStore();

        store.putReconciled(MPESA_TXN_ID, true);
        assertTrue(store.getReconciled(MPESA_TXN_ID));
        store.removeReconciled(MPESA_TXN_ID);
        assertNull(store.getReconciled(MPESA_TXN_ID));

        store.putWorkflowInstance(MPESA_TXN_ID, "workflow-456");
        assertEquals("workflow-456", store.getWorkflowInstance(MPESA_TXN_ID));
        store.removeWorkflowInstance(MPESA_TXN_ID);
        assertNull(store.getWorkflowInstance(MPESA_TXN_ID));

        verify(redisTemplate, never()).opsForValue();
        verifyNoInteractions(valueOperations);
    }

    @Test
    void memoryStore_shouldIsolateKeys() {
        InMemoryPaybillStateStore store = memoryStore();

        store.putWorkflowInstance(MPESA_TXN_ID, "workflow-a");
        store.putWorkflowInstance(OTHER_TXN_ID, "workflow-b");
        store.putReconciled(MPESA_TXN_ID, true);
        store.putReconciled(OTHER_TXN_ID, false);

        assertEquals("workflow-a", store.getWorkflowInstance(MPESA_TXN_ID));
        assertEquals("workflow-b", store.getWorkflowInstance(OTHER_TXN_ID));
        assertTrue(store.getReconciled(MPESA_TXN_ID));
        assertFalse(store.getReconciled(OTHER_TXN_ID));

        store.removeWorkflowInstance(MPESA_TXN_ID);
        store.removeReconciled(MPESA_TXN_ID);

        assertNull(store.getWorkflowInstance(MPESA_TXN_ID));
        assertEquals("workflow-b", store.getWorkflowInstance(OTHER_TXN_ID));
        assertNull(store.getReconciled(MPESA_TXN_ID));
        assertFalse(store.getReconciled(OTHER_TXN_ID));
    }

    @Test
    void logStoreBackend_shouldRunForBothBackends() {
        redisStore().logStoreBackend();
        memoryStore().logStoreBackend();
    }

    private RedisPaybillStateStore redisStore() {
        return new RedisPaybillStateStore(redisTemplate, redisProperties());
    }

    private InMemoryPaybillStateStore memoryStore() {
        return new InMemoryPaybillStateStore(redisProperties());
    }

    private RedisStoreProperties redisProperties() {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        RedisStoreProperties.Ttl ttl = new RedisStoreProperties.Ttl();
        ttl.setPaybillReconciledSeconds(RECONCILED_TTL_SECONDS);
        ttl.setPaybillWorkflowSeconds(WORKFLOW_TTL_SECONDS);
        properties.setTtl(ttl);
        return properties;
    }

    private String reconciledKey(String mpesaTxnId) {
        return KEY_PREFIX + ":paybill:reconciled:" + mpesaTxnId;
    }

    private String workflowKey(String mpesaTxnId) {
        return KEY_PREFIX + ":paybill:workflow:" + mpesaTxnId;
    }
}
