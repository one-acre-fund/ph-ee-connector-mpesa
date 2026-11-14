package org.mifos.connector.mpesa.flowcomponents.transaction;

import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.channel.dto.TransactionChannelC2BRequestDTO;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.resilience.MpesaHttpCaller;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.mifos.connector.mpesa.utility.ZeebeUtils;
import org.mifos.connector.mpesa.zeebe.ZeebeCommandHelper;
import org.mifos.connector.mpesa.zeebe.ZeebeWorkerDrain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.mifos.connector.mpesa.flowcomponents.PaybillStateStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ZeebeWorkerDrain drain;

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

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @Value("${zeebe.init-transfer.wait-timer}")
    private int initTransferWaitTimer;

    @Value("${skip.enabled}")
    private Boolean skipMpesa;

    @Value("${zeebe.client.request-timeout}")
    private Duration requestTimeout;

    // Workers open only once the application is ready, after Camel and the web server have
    // started. Opened earlier, a starting pod activates jobs it cannot process yet, and a pod
    // replaced during startup would hold them until the broker job timeout (FD-1939).
    @EventListener(ApplicationReadyEvent.class)
    public void setupWorkers() {

        drain.register(zeebeClient.newWorker()
                .jobType("init-transfer")
                .handler(drain.track((client, job) -> {
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
                    } else {
                        TransactionChannelC2BRequestDTO channelRequest = objectMapper.readValue(
                                (String) variables.get("mpesaChannelRequest"), TransactionChannelC2BRequestDTO.class);
                        String ams = (String) variables.get(AMS);
                        BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO = safaricomUtils.channelRequestConvertor(
                                channelRequest, transactionId, ams);
                        logger.info(buyGoodsPaymentRequestDTO.toString());
                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsPaymentRequestDTO);
                        exchange.setProperty(CORRELATION_ID, transactionId);
                        exchange.setProperty(DEPLOYED_PROCESS, job.getBpmnProcessId());
                        exchange.setProperty(AMS, ams);
                        exchange.setProperty(MpesaHttpCaller.JOB_DEADLINE, job.getDeadline());

                        variables.put(BUY_GOODS_REQUEST_BODY, buyGoodsPaymentRequestDTO.toString());

                        producerTemplate.send("direct:buy-goods-base", exchange);
                        if (exchange.getException() != null) {
                            // The route threw without classifying the outcome, for example a Redis
                            // timeout after Safaricom accepted the request. The payment may or may not
                            // have gone out: completing would record a false success and a retry could
                            // charge twice, so stop here and let an operator resolve the incident.
                            logger.error("Buy goods route failed for transaction {}, raising an incident",
                                    transactionId, exchange.getException());
                            ZeebeCommandHelper.raiseIncident(client, job,
                                    "buy goods route failed, payment outcome unknown: " + exchange.getException(),
                                    requestTimeout, logger);
                            return;
                        }
                        variables.put(MPESA_API_RESPONSE, exchange.getProperty(MPESA_API_RESPONSE));
                        variables.put(PARTY_LOOKUP_FSP_ID, exchange.getProperty(PARTY_LOOKUP_FSP_ID));

                        boolean isTransactionFailed = exchange.getProperty(TRANSACTION_FAILED, boolean.class);
                        if (isTransactionFailed) {
                            variables.put(TRANSACTION_FAILED, true);
                            variables.put(TRANSFER_CREATE_FAILED, true);
                            variables.put(TRANSFER_RESPONSE_CREATE, ZeebeUtils.getTransferResponseCreateJson());
                            String errorBody = exchange.getProperty(ERROR_INFORMATION, String.class);
                            variables.put(ERROR_INFORMATION, errorBody);
                            variables.put(ERROR_CODE, exchange.getProperty(ERROR_CODE, String.class));
                            variables.put(ERROR_DESCRIPTION, exchange.getProperty(ERROR_DESCRIPTION, String.class));
                        } else {
                            String serverTransactionId = exchange.getProperty(SERVER_TRANSACTION_ID, String.class);
                            variables.put(TRANSACTION_FAILED, false);
                            variables.put(TRANSFER_CREATE_FAILED, false);
                            variables.put(SERVER_TRANSACTION_ID, serverTransactionId);
                        }
                    }

                    ZeebeCommandHelper.completeJob(client, job, variables, requestTimeout, logger);
                }))
                .name("init-transfer")
                .maxJobsActive(workerMaxJobs)
                .open());

        drain.register(zeebeClient.newWorker()
                .jobType("delete-workflow-instancekey")
                .handler(drain.track((client, job) -> {
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
                .open());
    }
}
