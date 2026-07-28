package org.mifos.connector.mpesa.zeebe;

import io.camunda.zeebe.client.api.ZeebeFuture;
import io.camunda.zeebe.client.api.command.ClientException;
import io.camunda.zeebe.client.api.command.CompleteJobCommandStep1;
import io.camunda.zeebe.client.api.command.FailJobCommandStep1;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.response.CompleteJobResponse;
import io.camunda.zeebe.client.api.response.FailJobResponse;
import io.camunda.zeebe.client.api.worker.JobClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZeebeCommandHelperTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long JOB_KEY = 42L;
    private static final Logger LOGGER = LoggerFactory.getLogger(ZeebeCommandHelperTest.class);

    @Mock
    private JobClient jobClient;

    @Mock
    private ActivatedJob job;

    @Mock
    private CompleteJobCommandStep1 completeCommand;

    @Mock
    private FailJobCommandStep1 failCommandStep1;

    @Mock
    private FailJobCommandStep1.FailJobCommandStep2 failCommandStep2;

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void join_returnsFutureResult() {
        @SuppressWarnings("unchecked")
        ZeebeFuture<String> future = mock(ZeebeFuture.class);
        when(future.join(eq(30_000L), eq(TimeUnit.MILLISECONDS))).thenReturn("ok");

        assertEquals("ok", ZeebeCommandHelper.join(future, TIMEOUT));
    }

    @Test
    void join_usesTimeoutMillis() {
        @SuppressWarnings("unchecked")
        ZeebeFuture<String> future = mock(ZeebeFuture.class);
        when(future.join(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn("ok");

        ZeebeCommandHelper.join(future, Duration.ofSeconds(5));

        verify(future).join(5_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void join_rethrowsRuntimeExceptionWithoutSettingInterrupt() {
        @SuppressWarnings("unchecked")
        ZeebeFuture<String> future = mock(ZeebeFuture.class);
        when(future.join(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new ClientException("timeout"));

        assertThrows(ClientException.class, () -> ZeebeCommandHelper.join(future, TIMEOUT));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void join_restoresInterruptWhenCauseIsInterruptedException() {
        @SuppressWarnings("unchecked")
        ZeebeFuture<String> future = mock(ZeebeFuture.class);
        when(future.join(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new ClientException("interrupted", new InterruptedException("stopped")));

        assertThrows(ClientException.class, () -> ZeebeCommandHelper.join(future, TIMEOUT));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void completeJob_withVariables_sendsCompleteCommand() {
        when(job.getKey()).thenReturn(JOB_KEY);
        @SuppressWarnings("unchecked")
        ZeebeFuture<CompleteJobResponse> future = mock(ZeebeFuture.class);
        when(jobClient.newCompleteCommand(JOB_KEY)).thenReturn(completeCommand);
        when(completeCommand.variables(anyMap())).thenReturn(completeCommand);
        when(completeCommand.send()).thenReturn(future);
        when(future.join(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(mock(CompleteJobResponse.class));

        Map<String, Object> variables = Map.of("transactionFailed", false);
        ZeebeCommandHelper.completeJob(jobClient, job, variables, TIMEOUT, LOGGER);

        verify(completeCommand).variables(variables);
        verify(completeCommand).send();
        verify(future).join(30_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void completeJob_withNullVariables_sendsCompleteWithoutVariables() {
        when(job.getKey()).thenReturn(JOB_KEY);
        @SuppressWarnings("unchecked")
        ZeebeFuture<CompleteJobResponse> future = mock(ZeebeFuture.class);
        when(jobClient.newCompleteCommand(JOB_KEY)).thenReturn(completeCommand);
        when(completeCommand.send()).thenReturn(future);
        when(future.join(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(mock(CompleteJobResponse.class));

        ZeebeCommandHelper.completeJob(jobClient, job, null, TIMEOUT, LOGGER);

        verify(completeCommand).send();
        verify(future).join(30_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void completeJob_onFailure_attemptsFailWithDecrementedRetries() {
        stubJobKeyAndRetries(3);
        @SuppressWarnings("unchecked")
        ZeebeFuture<CompleteJobResponse> completeFuture = mock(ZeebeFuture.class);
        @SuppressWarnings("unchecked")
        ZeebeFuture<FailJobResponse> failFuture = mock(ZeebeFuture.class);

        when(jobClient.newCompleteCommand(JOB_KEY)).thenReturn(completeCommand);
        when(completeCommand.send()).thenReturn(completeFuture);
        when(completeFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new ClientException("complete timed out"));

        when(jobClient.newFailCommand(JOB_KEY)).thenReturn(failCommandStep1);
        when(failCommandStep1.retries(2)).thenReturn(failCommandStep2);
        when(failCommandStep2.errorMessage(anyString())).thenReturn(failCommandStep2);
        when(failCommandStep2.send()).thenReturn(failFuture);
        when(failFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(mock(FailJobResponse.class));

        ZeebeCommandHelper.completeJob(jobClient, job, null, TIMEOUT, LOGGER);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(failCommandStep1).retries(2);
        verify(failCommandStep2).errorMessage(errorCaptor.capture());
        assertTrue(errorCaptor.getValue().contains("Complete command failed"));
        verify(failFuture).join(30_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void completeJob_whenFailAlsoFails_doesNotThrow() {
        stubJobKeyAndRetries(1);
        @SuppressWarnings("unchecked")
        ZeebeFuture<CompleteJobResponse> completeFuture = mock(ZeebeFuture.class);
        @SuppressWarnings("unchecked")
        ZeebeFuture<FailJobResponse> failFuture = mock(ZeebeFuture.class);

        when(jobClient.newCompleteCommand(JOB_KEY)).thenReturn(completeCommand);
        when(completeCommand.send()).thenReturn(completeFuture);
        when(completeFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new ClientException("complete timed out"));

        when(jobClient.newFailCommand(JOB_KEY)).thenReturn(failCommandStep1);
        when(failCommandStep1.retries(0)).thenReturn(failCommandStep2);
        when(failCommandStep2.errorMessage(anyString())).thenReturn(failCommandStep2);
        when(failCommandStep2.send()).thenReturn(failFuture);
        when(failFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new ClientException("fail timed out"));

        ZeebeCommandHelper.completeJob(jobClient, job, null, TIMEOUT, LOGGER);

        verify(failCommandStep1).retries(0);
    }

    @Test
    void completeJob_onInterruptedFailure_restoresInterruptFlag() {
        stubJobKeyAndRetries(2);
        @SuppressWarnings("unchecked")
        ZeebeFuture<CompleteJobResponse> completeFuture = mock(ZeebeFuture.class);
        @SuppressWarnings("unchecked")
        ZeebeFuture<FailJobResponse> failFuture = mock(ZeebeFuture.class);

        when(jobClient.newCompleteCommand(JOB_KEY)).thenReturn(completeCommand);
        when(completeCommand.send()).thenReturn(completeFuture);
        when(completeFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new ClientException("interrupted", new InterruptedException("stopped")));

        when(jobClient.newFailCommand(JOB_KEY)).thenReturn(failCommandStep1);
        when(failCommandStep1.retries(anyInt())).thenReturn(failCommandStep2);
        when(failCommandStep2.errorMessage(anyString())).thenReturn(failCommandStep2);
        when(failCommandStep2.send()).thenReturn(failFuture);
        when(failFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(mock(FailJobResponse.class));

        ZeebeCommandHelper.completeJob(jobClient, job, null, TIMEOUT, LOGGER);

        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void failJob_sendsFailCommandWithBoundedJoin() {
        stubJobKeyAndRetries(5);
        @SuppressWarnings("unchecked")
        ZeebeFuture<FailJobResponse> failFuture = mock(ZeebeFuture.class);

        when(jobClient.newFailCommand(JOB_KEY)).thenReturn(failCommandStep1);
        when(failCommandStep1.retries(4)).thenReturn(failCommandStep2);
        when(failCommandStep2.errorMessage("boom")).thenReturn(failCommandStep2);
        when(failCommandStep2.send()).thenReturn(failFuture);
        when(failFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(mock(FailJobResponse.class));

        ZeebeCommandHelper.failJob(jobClient, job, "boom", TIMEOUT, LOGGER);

        verify(failCommandStep1).retries(4);
        verify(failCommandStep2).errorMessage("boom");
        verify(failFuture).join(30_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void failJob_withZeroRetries_doesNotGoNegative() {
        stubJobKeyAndRetries(0);
        @SuppressWarnings("unchecked")
        ZeebeFuture<FailJobResponse> failFuture = mock(ZeebeFuture.class);

        when(jobClient.newFailCommand(JOB_KEY)).thenReturn(failCommandStep1);
        when(failCommandStep1.retries(0)).thenReturn(failCommandStep2);
        when(failCommandStep2.errorMessage(anyString())).thenReturn(failCommandStep2);
        when(failCommandStep2.send()).thenReturn(failFuture);
        when(failFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(mock(FailJobResponse.class));

        ZeebeCommandHelper.failJob(jobClient, job, "no retries left", TIMEOUT, LOGGER);

        verify(failCommandStep1).retries(0);
    }

    @Test
    void failJob_onInterruptedFailure_restoresInterruptFlagAndDoesNotThrow() {
        stubJobKeyAndRetries(2);
        @SuppressWarnings("unchecked")
        ZeebeFuture<FailJobResponse> failFuture = mock(ZeebeFuture.class);

        when(jobClient.newFailCommand(JOB_KEY)).thenReturn(failCommandStep1);
        when(failCommandStep1.retries(anyInt())).thenReturn(failCommandStep2);
        when(failCommandStep2.errorMessage(anyString())).thenReturn(failCommandStep2);
        when(failCommandStep2.send()).thenReturn(failFuture);
        when(failFuture.join(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new ClientException("interrupted", new InterruptedException("stopped")));

        ZeebeCommandHelper.failJob(jobClient, job, "boom", TIMEOUT, LOGGER);

        assertTrue(Thread.currentThread().isInterrupted());
    }

    private void stubJobKeyAndRetries(int retries) {
        when(job.getKey()).thenReturn(JOB_KEY);
        when(job.getRetries()).thenReturn(retries);
    }
}
