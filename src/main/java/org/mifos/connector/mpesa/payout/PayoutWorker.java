package org.mifos.connector.mpesa.payout;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.mpesa.resilience.MpesaHttpCaller;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.utility.ZeebeUtils;
import org.mifos.connector.mpesa.zeebe.ZeebeCommandHelper;
import org.mifos.connector.mpesa.zeebe.ZeebeWorkerDrain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_HOST;
import static org.mifos.connector.mpesa.payout.PayoutVariables.B2C_REQUEST_BODY;
import static org.mifos.connector.mpesa.payout.PayoutVariables.Outcome;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCEPTED;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCOUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_CONVERSATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_FAILED;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_OUTCOME;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_REQUEST;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_RESPONSE_CREATE;

/**
 * {@code init-mpesa-payout}: sends the B2C payment request for a payout.
 *
 * <p>Completes with {@code payoutFailed=true} only when nothing can have been paid (invalid request, rejected or never
 * sent). Otherwise completes with {@code payoutFailed=false} and the process waits for the {@code payout-result}
 * message. A retried job sends the same OriginatorConversationID, which Safaricom rejects as a duplicate, so the
 * payout is never made twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutWorker {

    public static final String JOB_TYPE = "init-mpesa-payout";

    private final ZeebeClient zeebeClient;
    private final ZeebeWorkerDrain drain;
    private final ProducerTemplate producerTemplate;
    private final CamelContext camelContext;
    private final MpesaUtils mpesaUtils;
    private final PayoutRequestBuilder requestBuilder;
    private final PayoutStore payoutStore;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @Value("${zeebe.client.request-timeout}")
    private Duration requestTimeout;

    @EventListener(ApplicationReadyEvent.class)
    public void setupWorkers() {
        drain.register(zeebeClient.newWorker()
                .jobType(JOB_TYPE)
                .handler(drain.track(this::initPayout))
                .name(JOB_TYPE)
                .maxJobsActive(workerMaxJobs)
                .open());
    }

    void initPayout(JobClient client, ActivatedJob job) {
        var variables = job.getVariablesAsMap();
        var transactionId = (String) variables.get(TRANSACTION_ID);
        log.info("Job '{}' started for payout {} from process '{}' with key {}", job.getType(), transactionId,
                job.getBpmnProcessId(), job.getKey());

        var account = mpesaUtils.getMpesaProperties((String) variables.get(AMS), transactionId);
        B2CPaymentRequestDTO request;
        try {
            request = requestBuilder.build(transactionId, (String) variables.get(PAYOUT_REQUEST), account);
        } catch (PayoutValidationException e) {
            log.warn("Rejecting payout {} before calling M-Pesa: {} - {}", transactionId, e.getErrorCode(),
                    e.getMessage());
            ZeebeCommandHelper.completeJob(client, job, failed(e.getErrorCode(), e.getMessage(), e.getMessage()),
                    requestTimeout, log);
            return;
        }

        // Saved before sending, so the result callback can always be checked against it
        payoutStore.save(new PayoutRecord(transactionId, account.getName(), request.getAmount(), request.getPartyB(),
                System.currentTimeMillis()));

        var exchange = new DefaultExchange(camelContext);
        exchange.setProperty(CORRELATION_ID, transactionId);
        exchange.setProperty(PAYOUT_ACCOUNT, account);
        exchange.setProperty(B2C_REQUEST_BODY, request);
        exchange.setProperty(MPESA_API_HOST, account.getApiHost());
        exchange.setProperty(MpesaHttpCaller.JOB_DEADLINE, job.getDeadline());
        producerTemplate.send("direct:b2c-payment-base", exchange);

        if (exchange.getException() != null) {
            // The route failed without classifying the outcome: the payout may have been sent. Completing could
            // release the AMS reservation for money already paid, so stop and let an operator resolve it.
            log.error("B2C payment route failed for payout {}, raising an incident", transactionId,
                    exchange.getException());
            ZeebeCommandHelper.raiseIncident(client, job,
                    "B2C payment route failed, payout outcome unknown: " + exchange.getException(),
                    requestTimeout, log);
            return;
        }

        var outcome = exchange.getProperty(PAYOUT_OUTCOME, Outcome.UNKNOWN, Outcome.class);
        var completion = switch (outcome) {
            case FAILED -> failed(exchange.getProperty(ERROR_CODE, String.class),
                    exchange.getProperty(ERROR_DESCRIPTION, String.class),
                    exchange.getProperty(ERROR_INFORMATION, String.class));
            case ACCEPTED, UNKNOWN -> sent(outcome == Outcome.ACCEPTED,
                    exchange.getProperty(PAYOUT_CONVERSATION_ID, String.class));
        };
        log.info("Payout {} sent to M-Pesa with outcome {}", transactionId, outcome);
        ZeebeCommandHelper.completeJob(client, job, completion, requestTimeout, log);
    }

    private static Map<String, Object> sent(boolean accepted, String conversationId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(PAYOUT_FAILED, false);
        variables.put(PAYOUT_ACCEPTED, accepted);
        variables.put(PAYOUT_CONVERSATION_ID, conversationId);
        return variables;
    }

    private static Map<String, Object> failed(String errorCode, String errorDescription, String errorInformation) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(PAYOUT_FAILED, true);
        variables.put(PAYOUT_ACCEPTED, false);
        variables.put(TRANSFER_RESPONSE_CREATE, ZeebeUtils.getTransferResponseCreateJson());
        variables.put(ERROR_CODE, errorCode);
        variables.put(ERROR_DESCRIPTION, errorDescription);
        variables.put(ERROR_INFORMATION, errorInformation);
        return variables;
    }
}
