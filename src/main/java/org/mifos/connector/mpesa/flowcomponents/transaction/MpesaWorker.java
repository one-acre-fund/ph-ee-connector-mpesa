package org.mifos.connector.mpesa.flowcomponents.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.channel.dto.TransactionChannelC2BRequestDTO;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.mifos.connector.mpesa.utility.ZeebeUtils;
import org.mifos.connector.mpesa.zeebe.ZeebeCommandHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.mifos.connector.mpesa.flowcomponents.PaymentAttempt;
import org.mifos.connector.mpesa.flowcomponents.PaymentAttemptStore;
import org.mifos.connector.mpesa.flowcomponents.PaybillStateStore;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.BUY_GOODS_REQUEST_BODY;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.DEPLOYED_PROCESS;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_RESPONSE;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_CREATE_FAILED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_RESPONSE_CREATE;

@Component
public class MpesaWorker {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    static final String OUTCOME_UNKNOWN = "PAYMENT_OUTCOME_UNKNOWN";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private SafaricomUtils safaricomUtils;

    @Autowired
    private MpesaUtils mpesaUtils;

    @Autowired
    private PaybillStateStore paybillStateStore;

    @Autowired
    private PaymentAttemptStore paymentAttemptStore;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @Value("${zeebe.init-transfer.wait-timer}")
    private int initTransferWaitTimer;

    @Value("${zeebe.init-transfer.pending-retry-backoff}")
    private Duration pendingRetryBackoff;

    @Value("${zeebe.init-transfer.pending-timeout}")
    private Duration pendingTimeout;

    @Value("${skip.enabled}")
    private Boolean skipMpesa;

    @Value("${zeebe.client.request-timeout}")
    private Duration requestTimeout;

    @PostConstruct
    public void setupWorkers() {

        zeebeClient.newWorker()
                .jobType("init-transfer")
                .handler((client, job) -> {
                    logger.info("Job '{}' started from process '{}' with key {} and workflow instance key {}",
                        job.getType(), job.getBpmnProcessId(), job.getKey(), job.getProcessInstanceKey());
                    long t1 = System.currentTimeMillis();
                    logger.info("Going to sleep at " + t1 + " for " + initTransferWaitTimer + " seconds");
                    ZeebeUtils.sleep(initTransferWaitTimer);
                    long t2 = System.currentTimeMillis();
                    logger.info("I am awake at " + t2);
                    logger.info("Time diff " + (t2 - t1));

                    Map<String, Object> variables = job.getVariablesAsMap();
                    mpesaUtils.setProcess(job.getBpmnProcessId());
                    String transactionId = (String) variables.get(TRANSACTION_ID);
                    if (skipMpesa) {
                        logger.info("Skipping MPESA for transaction with id {}", transactionId);
                        Exchange exchange = new DefaultExchange(camelContext);
                        String serverTransactionId = exchange.getProperty(SERVER_TRANSACTION_ID, String.class);
                        variables.put(TRANSACTION_FAILED, false);
                        variables.put(TRANSFER_CREATE_FAILED, false);
                        variables.put(SERVER_TRANSACTION_ID, serverTransactionId);
                    } else if (!paymentAttemptStore.claim(requireTransactionId(transactionId, job))) {
                        PaymentAttempt previous = paymentAttemptStore.result(transactionId).orElse(null);
                        if (previous == null) {
                            waitForPendingAttempt(client, job, transactionId);
                            return;
                        }
                        logger.warn("Transaction {} was already attempted; completing with that outcome without calling M-Pesa again",
                                transactionId);
                        applyPreviousAttempt(previous, variables);
                    } else {
                        Exchange exchange = new DefaultExchange(camelContext);
                        try {
                            TransactionChannelC2BRequestDTO channelRequest = objectMapper.readValue(
                                    (String) variables.get("mpesaChannelRequest"), TransactionChannelC2BRequestDTO.class);
                            String ams = (String) variables.get(AMS);
                            BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO = safaricomUtils.channelRequestConvertor(
                                    channelRequest, transactionId, ams);
                            logger.info(buyGoodsPaymentRequestDTO.toString());
                            exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsPaymentRequestDTO);
                            exchange.setProperty(CORRELATION_ID, transactionId);
                            exchange.setProperty(DEPLOYED_PROCESS, job.getBpmnProcessId());
                            exchange.setProperty(AMS, ams);

                            variables.put(BUY_GOODS_REQUEST_BODY, buyGoodsPaymentRequestDTO.toString());
                        } catch (Exception e) {
                            paymentAttemptStore.release(transactionId);
                            throw e;
                        }

                        producerTemplate.send("direct:buy-goods-base", exchange);
                        variables.put(MPESA_API_RESPONSE, exchange.getProperty(MPESA_API_RESPONSE));
                        variables.put(PARTY_LOOKUP_FSP_ID, exchange.getProperty(PARTY_LOOKUP_FSP_ID));

                        boolean isTransactionFailed = exchange.getProperty(TRANSACTION_FAILED, boolean.class);
                        String serverTransactionId = exchange.getProperty(SERVER_TRANSACTION_ID, String.class);
                        String errorCode = exchange.getProperty(ERROR_CODE, String.class);
                        String errorDescription = exchange.getProperty(ERROR_DESCRIPTION, String.class);
                        String errorBody = exchange.getProperty(ERROR_INFORMATION, String.class);
                        if (isTransactionFailed) {
                            variables.put(TRANSACTION_FAILED, true);
                            variables.put(TRANSFER_CREATE_FAILED, true);
                            variables.put(TRANSFER_RESPONSE_CREATE, ZeebeUtils.getTransferResponseCreateJson());
                            variables.put(ERROR_INFORMATION, errorBody);
                            variables.put(ERROR_CODE, errorCode);
                            variables.put(ERROR_DESCRIPTION, errorDescription);
                        } else {
                            variables.put(TRANSACTION_FAILED, false);
                            variables.put(TRANSFER_CREATE_FAILED, false);
                            variables.put(SERVER_TRANSACTION_ID, serverTransactionId);
                        }
                        paymentAttemptStore.recordResult(transactionId, new PaymentAttempt(
                                isTransactionFailed, serverTransactionId, errorCode, errorDescription, errorBody));
                    }

                    ZeebeCommandHelper.completeJob(client, job, variables, requestTimeout, logger);
                })
                .name("init-transfer")
                .maxJobsActive(workerMaxJobs)
                .open();

        zeebeClient.newWorker()
                .jobType("delete-workflow-instancekey")
                .handler(((client, job) -> {
                    logger.info("Removing Workflow Instance key and Mpesa Txn Id from store");
                    Map<String, Object> variables = job.getVariablesAsMap();
                    String mpesaTxnId = variables.get("mpesaTxnId").toString();
                    logger.debug("Txn Id Removed :{}", mpesaTxnId);
                    paybillStateStore.removeWorkflowInstance(mpesaTxnId);
                    Map<String, Object> completionVariables = new HashMap<>();
                    completionVariables.put(TRANSFER_CREATE_FAILED, true);
                    ZeebeCommandHelper.completeJob(client, job, completionVariables, requestTimeout, logger);
                }))
                .name("Cleanup")
                .maxJobsActive(workerMaxJobs)
                .open();
    }

    private static String requireTransactionId(String transactionId, ActivatedJob job) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalStateException("init-transfer job " + job.getKey() + " has no transactionId");
        }
        return transactionId;
    }

    /**
     * A pending attempt is never completed. While the claim is younger than the pending timeout the
     * job is failed with its retry count intact and a back-off, so Zeebe keeps re-offering it until
     * the first attempt records its outcome. Past that age the first attempt is treated as dead: the
     * attempt is recorded as a failure with an unknown outcome, so that whenever the job runs again,
     * including after an operator resolves the incident, it completes from that record and never
     * calls the provider; then the job fails with no retries, raising the incident for reconciliation.
     */
    private void waitForPendingAttempt(JobClient client, ActivatedJob job, String transactionId) {
        Instant now = Instant.now();
        Instant since = paymentAttemptStore.pendingSince(transactionId).orElse(now);
        if (Duration.between(since, now).compareTo(pendingTimeout) > 0) {
            logger.error("Transaction {} has been pending since {} with no recorded outcome; raising an incident",
                    transactionId, since);
            paymentAttemptStore.recordResult(transactionId, new PaymentAttempt(true, null, OUTCOME_UNKNOWN,
                    "No provider outcome recorded within " + pendingTimeout + "; reconcile with the provider", null));
            ZeebeCommandHelper.failJob(client, job,
                    "Payment attempt for " + transactionId + " pending since " + since + " with no outcome; reconcile manually",
                    0, pendingRetryBackoff, requestTimeout, logger);
            return;
        }
        logger.warn("Transaction {} is still being attempted elsewhere; re-offering after {}",
                transactionId, pendingRetryBackoff);
        ZeebeCommandHelper.failJob(client, job,
                "Payment attempt for " + transactionId + " is still pending",
                Math.max(job.getRetries(), 1), pendingRetryBackoff, requestTimeout, logger);
    }

    /**
     * The provider was already called for this transaction by an earlier activation whose outcome
     * is recorded: complete with that outcome without calling the provider.
     */
    private static void applyPreviousAttempt(PaymentAttempt previous, Map<String, Object> variables) {
        variables.put(TRANSACTION_FAILED, previous.isFailed());
        variables.put(TRANSFER_CREATE_FAILED, previous.isFailed());
        if (previous.isFailed()) {
            variables.put(TRANSFER_RESPONSE_CREATE, ZeebeUtils.getTransferResponseCreateJson());
            variables.put(ERROR_INFORMATION, previous.getErrorInformation());
            variables.put(ERROR_CODE, previous.getErrorCode());
            variables.put(ERROR_DESCRIPTION, previous.getErrorDescription());
        } else {
            variables.put(SERVER_TRANSACTION_ID, previous.getServerTransactionId());
        }
    }
}
