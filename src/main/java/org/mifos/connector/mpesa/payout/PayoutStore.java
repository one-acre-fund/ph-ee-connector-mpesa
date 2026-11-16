package org.mifos.connector.mpesa.payout;

/**
 * Payouts sent to Safaricom, by transaction id (which is also the B2C OriginatorConversationID).
 */
public interface PayoutStore {

    void save(PayoutRecord record);

    /**
     * @return the payout, or null when it was not sent by this connector or has expired
     */
    PayoutRecord find(String transactionId);
}
