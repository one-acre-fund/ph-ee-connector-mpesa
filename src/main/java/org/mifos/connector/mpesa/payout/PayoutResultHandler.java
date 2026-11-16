package org.mifos.connector.mpesa.payout;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.command.ClientStatusException;
import io.grpc.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.mifos.connector.mpesa.zeebe.ZeebeCommandHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_COMPLETED_TIME;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_CONVERSATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_FAILED;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_RECEIVER_NAME;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_RESULT_MESSAGE;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_RECEIPT_NUMBER;

/**
 * Turns the results Safaricom posts for payouts into the {@code payout-result} message of the payout process.
 *
 * <p>A result is only accepted for a payout this connector sent, and a success only when the amount matches what was
 * sent. Anything else is logged and dropped: the payout stays unknown, the status checks run, and an operator resolves
 * it if they cannot. The message id is the same for every result of a payout, so Zeebe accepts the first final outcome
 * and drops any repeat (a duplicate callback, or a status result after the payment result).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutResultHandler {

    private static final String COMPLETED = "Completed";

    private final ZeebeClient zeebeClient;
    private final PayoutStore payoutStore;
    @Value("${zeebe.client.request-timeout}")
    private final Duration requestTimeout;
    @Value("${mpesa.payout.message-ttl}")
    private final Duration messageTtl;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final Map<String, AtomicReference<Double>> balances = new ConcurrentHashMap<>();

    /**
     * Handles the result of a B2C payment request.
     */
    public void onPaymentResult(String body) {
        var result = parse(body);
        if (result == null) {
            return;
        }
        String transactionId = result.originatorConversationId();
        var payout = sentPayout(transactionId, "payment");
        if (payout == null) {
            return;
        }
        Map<String, Object> variables;
        if (result.isSuccess()) {
            if (!amountMatches(payout, result.parameter("TransactionAmount"))) {
                return;
            }
            String receipt = result.parameter("TransactionReceipt");
            variables = success(receipt != null ? receipt : result.transactionId(),
                    result.parameter("ReceiverPartyPublicName"), result.parameter("TransactionCompletedDateTime"));
            variables.put(PAYOUT_CONVERSATION_ID, result.conversationId());
            recordBalance(payout.account(), result.parameter("B2CUtilityAccountAvailableFunds"));
        } else {
            variables = new HashMap<>();
            variables.put(PAYOUT_FAILED, true);
            variables.put(ERROR_CODE, result.resultCode());
            variables.put(ERROR_DESCRIPTION, result.resultDesc());
            variables.put(ERROR_INFORMATION, body);
        }
        log.info("event=payout_result transaction={} failed={} code={} description={}", transactionId,
                variables.get(PAYOUT_FAILED), result.resultCode(), result.resultDesc());
        publish(transactionId, variables);
    }

    /**
     * Handles the result of a transaction status query. Only a completed payout is final; anything else leaves it
     * unknown, because a status query cannot prove a payout will never be made.
     */
    public void onStatusResult(String body) {
        var result = parse(body);
        if (result == null) {
            return;
        }
        String transactionId = result.referenceData("Occasion");
        if (transactionId == null) {
            transactionId = result.parameter("OriginatorConversationID");
        }
        var payout = sentPayout(transactionId, "status");
        if (payout == null) {
            return;
        }
        String queried = result.parameter("OriginatorConversationID");
        if (queried != null && !queried.equals(transactionId)) {
            log.error("event=payout_status_mismatch transaction={} result_for={}", transactionId, queried);
            return;
        }
        String status = result.parameter("TransactionStatus");
        if (!result.isSuccess() || !COMPLETED.equalsIgnoreCase(status)) {
            log.info("event=payout_status_not_final transaction={} code={} description={} status={}",
                    transactionId, result.resultCode(), result.resultDesc(), status);
            return;
        }
        if (!amountMatches(payout, result.parameter("Amount"))) {
            return;
        }
        Map<String, Object> variables = success(result.parameter("ReceiptNo"), result.parameter("CreditPartyName"),
                result.parameter("FinalisedTime"));
        log.info("event=payout_result transaction={} failed=false source=status_query", transactionId);
        publish(transactionId, variables);
    }

    private static B2CResult parse(String body) {
        try {
            return B2CResult.parse(body);
        } catch (JSONException | NullPointerException e) {
            log.error("event=payout_result_unreadable body={}", body);
            return null;
        }
    }

    private PayoutRecord sentPayout(String transactionId, String kind) {
        var payout = transactionId == null ? null : payoutStore.find(transactionId);
        if (payout == null) {
            log.error("event=payout_result_unknown kind={} transaction={}: no payout was sent with this id",
                    kind, transactionId);
        }
        return payout;
    }

    private static boolean amountMatches(PayoutRecord payout, String amount) {
        try {
            if (amount != null && new BigDecimal(amount.trim()).compareTo(BigDecimal.valueOf(payout.amount())) == 0) {
                return true;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        log.error("event=payout_result_mismatch transaction={} sent_amount={} result_amount={}",
                payout.transactionId(), payout.amount(), amount);
        return false;
    }

    private static Map<String, Object> success(String receipt, String receiver, String completedTime) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(PAYOUT_FAILED, false);
        variables.put(SERVER_TRANSACTION_RECEIPT_NUMBER, receipt);
        variables.put(PAYOUT_RECEIVER_NAME, receiver);
        variables.put(PAYOUT_COMPLETED_TIME, completedTime);
        return variables;
    }

    private void publish(String transactionId, Map<String, Object> variables) {
        try {
            ZeebeCommandHelper.join(zeebeClient.newPublishMessageCommand()
                    .messageName(PAYOUT_RESULT_MESSAGE)
                    .correlationKey(transactionId)
                    .messageId(PAYOUT_RESULT_MESSAGE + "-" + transactionId)
                    .timeToLive(messageTtl)
                    .variables(variables)
                    .send(), requestTimeout);
        } catch (ClientStatusException e) {
            if (e.getStatusCode() != Status.Code.ALREADY_EXISTS) {
                throw e;
            }
            log.info("event=payout_result_duplicate transaction={}: an outcome was already published",
                    transactionId);
        }
    }

    private void recordBalance(String account, String balance) {
        if (balance == null || account == null) {
            return;
        }
        try {
            double value = Double.parseDouble(balance.trim());
            balances.computeIfAbsent(account, name -> {
                AtomicReference<Double> reference = new AtomicReference<>(value);
                meterRegistry.ifAvailable(registry -> Gauge.builder("mpesa.b2c.utility.balance", reference,
                        AtomicReference::get).tag("account", name).register(registry));
                return reference;
            }).set(value);
        } catch (NumberFormatException e) {
            log.warn("event=payout_balance_unreadable account={} value={}", account, balance);
        }
    }
}
