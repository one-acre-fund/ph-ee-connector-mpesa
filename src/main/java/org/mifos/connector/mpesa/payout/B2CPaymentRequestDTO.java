package org.mifos.connector.mpesa.payout;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.mifos.connector.mpesa.utility.MpesaUtils;

/**
 * Request body of the Safaricom B2C v3 payment request ({@code /mpesa/b2c/v3/paymentrequest}).
 */
@Getter
@Setter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class B2CPaymentRequestDTO {

    // Our id for the payout. Safaricom rejects a second request with the same id, so a payout is never sent twice.
    @JsonProperty("OriginatorConversationID")
    private String originatorConversationId;

    @JsonProperty("InitiatorName")
    private String initiatorName;

    @ToString.Exclude
    @JsonProperty("SecurityCredential")
    private String securityCredential;

    @JsonProperty("CommandID")
    private String commandId;

    @JsonProperty("Amount")
    private Long amount;

    @JsonProperty("PartyA")
    private Long partyA;

    @JsonProperty("PartyB")
    private Long partyB;

    @JsonProperty("Remarks")
    private String remarks;

    @JsonProperty("QueueTimeOutURL")
    private String queueTimeOutUrl;

    @JsonProperty("ResultURL")
    private String resultUrl;

    @JsonProperty("Occassion")
    private String occasion;

    @ToString.Include(name = "securityCredential")
    private String maskedSecurityCredential() {
        return MpesaUtils.maskString(securityCredential);
    }
}
