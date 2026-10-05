package org.mifos.connector.mpesa.zeebe;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.ZeebeFuture;
import io.camunda.zeebe.client.api.command.CompleteJobCommandStep1;
import io.camunda.zeebe.client.api.command.FailJobCommandStep1;
import io.camunda.zeebe.client.api.command.PublishMessageCommandStep1;
import io.camunda.zeebe.client.api.command.SetVariablesCommandStep1;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorkerBuilderStep1;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;


@SuppressWarnings({"unchecked", "rawtypes"})
public final class ZeebeMocks {

    public final ZeebeClient client = mock(ZeebeClient.class);
    public final PublishMessageCommandStep1.PublishMessageCommandStep3 publish =
            mock(PublishMessageCommandStep1.PublishMessageCommandStep3.class, RETURNS_SELF);
    public final PublishMessageCommandStep1.PublishMessageCommandStep2 publishStep2 =
            mock(PublishMessageCommandStep1.PublishMessageCommandStep2.class);
    public final PublishMessageCommandStep1 publishStep1 = mock(PublishMessageCommandStep1.class);
    public final SetVariablesCommandStep1.SetVariablesCommandStep2 setVariables =
            mock(SetVariablesCommandStep1.SetVariablesCommandStep2.class);
    public final SetVariablesCommandStep1 setVariablesStep1 = mock(SetVariablesCommandStep1.class);
    public final JobWorkerBuilderStep1.JobWorkerBuilderStep3 workerStep3 =
            mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class, RETURNS_SELF);
    public final JobWorkerBuilderStep1.JobWorkerBuilderStep2 workerStep2 =
            mock(JobWorkerBuilderStep1.JobWorkerBuilderStep2.class);
    public final JobWorkerBuilderStep1 workerStep1 = mock(JobWorkerBuilderStep1.class);
    public final ZeebeFuture future = mock(ZeebeFuture.class);

    public ZeebeMocks() {
        lenient().when(client.newPublishMessageCommand()).thenReturn(publishStep1);
        lenient().when(publishStep1.messageName(anyString())).thenReturn(publishStep2);
        lenient().when(publishStep2.correlationKey(any())).thenReturn(publish);
        lenient().when(publish.send()).thenReturn(future);

        lenient().when(client.newSetVariablesCommand(anyLong())).thenReturn(setVariablesStep1);
        lenient().when(setVariablesStep1.variables(anyMap())).thenReturn(setVariables);
        lenient().when(setVariables.send()).thenReturn(future);

        lenient().when(client.newWorker()).thenReturn(workerStep1);
        lenient().when(workerStep1.jobType(anyString())).thenReturn(workerStep2);
        lenient().when(workerStep2.handler(any())).thenReturn(workerStep3);
    }

    public Map<String, Object> publishedVariables() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publish).variables(captor.capture());
        return captor.getValue();
    }

    public Map<String, Object> setVariablesSent() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(setVariablesStep1).variables(captor.capture());
        return captor.getValue();
    }

    /** Handlers registered with {@code newWorker()}, in registration order. */
    public List<JobHandler> handlers() {
        ArgumentCaptor<JobHandler> captor = ArgumentCaptor.forClass(JobHandler.class);
        verify(workerStep2, org.mockito.Mockito.atLeastOnce()).handler(captor.capture());
        return captor.getAllValues();
    }

    /** A job client whose complete/fail commands succeed. */
    public static JobClient jobClient() {
        JobClient jobClient = mock(JobClient.class);
        CompleteJobCommandStep1 complete = mock(CompleteJobCommandStep1.class, RETURNS_SELF);
        FailJobCommandStep1 fail = mock(FailJobCommandStep1.class);
        FailJobCommandStep1.FailJobCommandStep2 failStep2 =
                mock(FailJobCommandStep1.FailJobCommandStep2.class, RETURNS_SELF);
        ZeebeFuture future = mock(ZeebeFuture.class);
        lenient().when(jobClient.newCompleteCommand(anyLong())).thenReturn(complete);
        lenient().when(complete.send()).thenReturn(future);
        lenient().when(jobClient.newFailCommand(anyLong())).thenReturn(fail);
        lenient().when(fail.retries(anyInt())).thenReturn(failStep2);
        lenient().when(failStep2.send()).thenReturn(future);
        return jobClient;
    }

    public static ActivatedJob job(Map<String, Object> variables) {
        ActivatedJob job = mock(ActivatedJob.class);
        lenient().when(job.getVariablesAsMap()).thenReturn(variables);
        lenient().when(job.getBpmnProcessId()).thenReturn("mpesa_flow_roster-kenya");
        lenient().when(job.getKey()).thenReturn(1L);
        lenient().when(job.getElementInstanceKey()).thenReturn(99L);
        lenient().when(job.getType()).thenReturn("job");
        return job;
    }
}
