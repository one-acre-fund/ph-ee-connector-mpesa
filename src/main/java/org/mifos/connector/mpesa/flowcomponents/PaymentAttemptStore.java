package org.mifos.connector.mpesa.flowcomponents;

import java.time.Instant;
import java.util.Optional;

/**
 * Records that a payment was attempted for a transaction id, so that a job re-activated after a
 * pod restart or timeout does not call the provider a second time.
 */
public interface PaymentAttemptStore {

    /** Claims the transaction for this attempt. False means an attempt was already made. */
    boolean claim(String transactionId);

    /** Drops a claim when the provider was never called, so a later attempt may proceed. */
    void release(String transactionId);

    void recordResult(String transactionId, PaymentAttempt result);

    /** Empty while the first attempt is still in progress or its outcome was never recorded. */
    Optional<PaymentAttempt> result(String transactionId);

    /** When the still-pending claim was made; empty once an outcome is recorded or no claim exists. */
    Optional<Instant> pendingSince(String transactionId);
}
