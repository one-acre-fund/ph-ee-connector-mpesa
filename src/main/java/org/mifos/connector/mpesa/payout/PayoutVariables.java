package org.mifos.connector.mpesa.payout;

/**
 * Zeebe variables, message names and exchange properties of the B2C payout flow.
 */
public final class PayoutVariables {

    private PayoutVariables() {
    }

    // Zeebe message published when the final payout outcome is known, from the B2C result or a status query result
    public static final String PAYOUT_RESULT_MESSAGE = "payout-result";

    // Zeebe variables
    public static final String PAYOUT_REQUEST = "payoutRequest";
    public static final String PAYOUT_FAILED = "payoutFailed";
    public static final String PAYOUT_ACCEPTED = "payoutAccepted";
    public static final String PAYOUT_CONVERSATION_ID = "payoutConversationId";
    public static final String PAYOUT_RECEIVER_NAME = "payoutReceiverName";
    public static final String PAYOUT_COMPLETED_TIME = "payoutCompletedTime";
    public static final String PAYOUT_STATUS_RETRY_COUNT = "payoutStatusRetryCount";
    public static final String PAYOUT_STATUS_CHECKS_EXHAUSTED = "payoutStatusChecksExhausted";
    public static final String PAYOUT_TIMER = "payoutTimer";

    // Exchange properties
    public static final String PAYOUT_ACCOUNT = "payoutAccount";
    public static final String B2C_REQUEST_BODY = "b2cRequestBody";
    public static final String PAYOUT_OUTCOME = "payoutOutcome";

    /**
     * What is known about a payout after the B2C request.
     */
    public enum Outcome {
        // Safaricom accepted the request; the result arrives on the ResultURL
        ACCEPTED,
        // The request was rejected or never sent: no money can have moved
        FAILED,
        // The request may have reached Safaricom: only a result or a status query can tell
        UNKNOWN
    }
}
