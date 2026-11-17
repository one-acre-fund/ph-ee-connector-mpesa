package org.mifos.connector.mpesa.payout;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.mifos.connector.mpesa.utility.MpesaUtils;

/**
 * Request body of the Safaricom transaction status query ({@code /mpesa/transactionstatus/v1/query}), used to find
 * the outcome of a payout by its OriginatorConversationID.
 */
@Getter
@Setter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class B2CTransactionStatusRequestDTO {

    public static final String COMMAND_ID = "TransactionStatusQuery";
    public static final String SHORTCODE_IDENTIFIER_TYPE = "4";

    @JsonProperty("Initiator")
    private String initiator;

    @ToString.Exclude
    @JsonProperty("SecurityCredential")
    private String securityCredential;

    @JsonProperty("CommandID")
    private String commandId = COMMAND_ID;

    @JsonProperty("OriginalConversationID")
    private String originalConversationId;

    @JsonProperty("PartyA")
    private Long partyA;

    @JsonProperty("IdentifierType")
    private String identifierType = SHORTCODE_IDENTIFIER_TYPE;

    @JsonProperty("ResultURL")
    private String resultUrl;

    @JsonProperty("QueueTimeOutURL")
    private String queueTimeOutUrl;

    @JsonProperty("Remarks")
    private String remarks;

    // Returned in the result's ReferenceData, so the result can be matched to the payout
    @JsonProperty("Occasion")
    private String occasion;

    @ToString.Include(name = "securityCredential")
    private String maskedSecurityCredential() {
        return MpesaUtils.maskString(securityCredential);
    }
}
