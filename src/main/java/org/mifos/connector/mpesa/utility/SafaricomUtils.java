package org.mifos.connector.mpesa.utility;

import org.apache.camel.util.json.JsonObject;
import org.mifos.connector.common.channel.dto.TransactionChannelC2BRequestDTO;
import org.mifos.connector.common.gsma.dto.GsmaParty;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.dto.StkCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.mifos.connector.mpesa.safaricom.config.SafaricomProperties.MPESA_BUY_GOODS_TRANSACTION_TYPE;
import static org.mifos.connector.mpesa.utility.ChannelRequestValidationException.INVALID_AMOUNT;
import static org.mifos.connector.mpesa.utility.ChannelRequestValidationException.INVALID_MSISDN;
import static org.mifos.connector.mpesa.utility.ChannelRequestValidationException.INVALID_PAYER;

@Component
public class SafaricomUtils {

    private static final String MSISDN_KEY = "MSISDN";
    // E.164 numbers are at most 15 digits, which also keeps the value within a long
    private static final Pattern MSISDN_PATTERN = Pattern.compile("\\d{1,15}");

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private MpesaUtils mpesaUtils;

    @Value("${mpesa.local.host}")
    private String host;

    @Value("${mpesa.local.transaction-callback}")
    private String callbackEndpoint;

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");


    public BuyGoodsPaymentRequestDTO channelRequestConvertor(TransactionChannelC2BRequestDTO transactionChannelRequestDTO, String transactionId, String ams) {
        logger.info("TransactionChannelCollectionRequestDTO chile converting " + transactionChannelRequestDTO);
        BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO = new BuyGoodsPaymentRequestDTO();
        MpesaProps.MPESA mpesaProps = mpesaUtils.getMpesaProperties(ams, transactionId);

        long amount = parseAmount(transactionChannelRequestDTO.getAmount() == null ? null
                : transactionChannelRequestDTO.getAmount().getAmount());
        long timestamp = getTimestamp();

        GsmaParty[] party = transactionChannelRequestDTO.getPayer();
        long payer = parseMsisdn(findMsisdn(party));
        String accountId = findAccountId(party);

        buyGoodsPaymentRequestDTO.setTimestamp(""+timestamp);
        buyGoodsPaymentRequestDTO.setCallBackURL(host + callbackEndpoint);

        buyGoodsPaymentRequestDTO.setPartyA(payer);
        buyGoodsPaymentRequestDTO.setPhoneNumber(payer);

        buyGoodsPaymentRequestDTO.setPartyB(mpesaProps.getTill());
        buyGoodsPaymentRequestDTO.setBusinessShortCode(mpesaProps.getBusinessShortCode());

        buyGoodsPaymentRequestDTO.setAmount(amount);
        buyGoodsPaymentRequestDTO.setPassword(getPassword(
                ""+mpesaProps.getBusinessShortCode(), mpesaProps.getPassKey(), "" + timestamp
        ));
        buyGoodsPaymentRequestDTO.setTransactionType(MPESA_BUY_GOODS_TRANSACTION_TYPE);
        buyGoodsPaymentRequestDTO.setTransactionDesc("Payment from account id" + accountId);
        buyGoodsPaymentRequestDTO.setAccountReference("Payment to " + mpesaProps.getBusinessShortCode());
        logger.info("Values from Safaricom utils: Shortcode - " + mpesaProps.getBusinessShortCode() + " Till -" +
                mpesaProps.getTill());

        return buyGoodsPaymentRequestDTO;
    }

    /**
     * Safaricom STK push only accepts whole KES amounts. "1315" and "1315.00" are accepted, "1315.5" is rejected
     * rather than rounded so the customer is never charged a different amount from the one in the AMS.
     */
    static long parseAmount(String rawAmount) {
        if (rawAmount == null || rawAmount.isBlank()) {
            throw new ChannelRequestValidationException(INVALID_AMOUNT, "Amount is missing");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount.trim());
        } catch (NumberFormatException e) {
            throw new ChannelRequestValidationException(INVALID_AMOUNT, "Amount '" + rawAmount + "' is not a number");
        }
        if (amount.signum() <= 0) {
            throw new ChannelRequestValidationException(INVALID_AMOUNT,
                    "Amount '" + rawAmount + "' must be greater than zero");
        }
        try {
            return amount.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            throw new ChannelRequestValidationException(INVALID_AMOUNT,
                    "Amount '" + rawAmount + "' must be a whole number, M-Pesa does not accept decimal amounts");
        }
    }

    /**
     * Accepts any digits-only MSISDN, with or without a leading '+'. No country-specific format is enforced.
     */
    static long parseMsisdn(String rawMsisdn) {
        String msisdn = rawMsisdn == null ? "" : rawMsisdn.trim();
        if (msisdn.startsWith("+")) {
            msisdn = msisdn.substring(1);
        }
        if (!MSISDN_PATTERN.matcher(msisdn).matches()) {
            throw new ChannelRequestValidationException(INVALID_MSISDN,
                    "Payer MSISDN '" + rawMsisdn + "' is missing or invalid");
        }
        return Long.parseLong(msisdn);
    }

    private static String findMsisdn(GsmaParty[] parties) {
        if (parties == null || parties.length == 0) {
            throw new ChannelRequestValidationException(INVALID_PAYER, "Payer is missing");
        }
        for (GsmaParty party : parties) {
            if (party != null && MSISDN_KEY.equalsIgnoreCase(party.getKey())) {
                return party.getValue();
            }
        }
        throw new ChannelRequestValidationException(INVALID_MSISDN, "Payer has no MSISDN");
    }

    private static String findAccountId(GsmaParty[] parties) {
        for (GsmaParty party : parties) {
            if (party != null && party.getKey() != null && !MSISDN_KEY.equalsIgnoreCase(party.getKey())) {
                return party.getValue();
            }
        }
        return "";
    }

    public Long getTimestamp() {
        return Long.parseLong(LocalDateTime.now(ZoneId.systemDefault()).format(TIMESTAMP_FORMAT));
    }

    /*
     * Return the result code from the callback received from mpesa server
     */
    public static StkCallback getStkCallback(JsonObject callback) {
        StkCallback stkCallback = new StkCallback();

        LinkedHashMap<String, Object> body = (LinkedHashMap<String, Object>) callback.get("Body");
        LinkedHashMap<String, Object> stkCallbackFromServer = (LinkedHashMap<String, Object>) body.get("stkCallback");
        stkCallback.setMerchantRequestId((String) stkCallbackFromServer.get("MerchantRequestID"));
        stkCallback.setCheckoutRequestId((String) stkCallbackFromServer.get("CheckoutRequestID"));
        stkCallback.setResultCode(Long.parseLong(String.valueOf(stkCallbackFromServer.get("ResultCode"))));
        stkCallback.setResultDesc((String) stkCallbackFromServer.get("ResultDesc"));

        return stkCallback;
    }

    /*
     * Return the transaction id from the callback received from mpesa server
     */
    public static String getTransactionId(JsonObject callback) {
        AtomicReference<String> mpesaReceiptNumber = new AtomicReference<>("");
        LinkedHashMap<String, Object> body = (LinkedHashMap<String, Object>) callback.get("Body");
        LinkedHashMap<String, Object> stkCallback = (LinkedHashMap<String, Object>) body.get("stkCallback");
        LinkedHashMap<String, Object> metaData = (LinkedHashMap<String, Object>) stkCallback.get("CallbackMetadata");
        ArrayList<Object> metaDataItems = (ArrayList) metaData.get("Item");

        metaDataItems.forEach(metaDataItem -> {
            LinkedHashMap<String, Object> item = (LinkedHashMap<String, Object>) metaDataItem;
            if(item.get("Name").equals("MpesaReceiptNumber")) {
                mpesaReceiptNumber.set((String) item.get("Value"));
            }
        });
        return String.valueOf(mpesaReceiptNumber);
    }

    /*
     * Generated the password using the businessShortCode, passKey and timestamp
     * @param businessShortCode
     * @param passKey
     * @param timestamp
     * @return password
     */
    public String getPassword(String businessShortCode, String passKey, String timestamp) {
        businessShortCode = businessShortCode.replace("\n", "");
        passKey = passKey.replace("\n", "");
        timestamp = timestamp.replace("\n", "");
        String data = businessShortCode + passKey + timestamp;
        return toBase64(data);
    }

    /*
     * Converts the string data into base64 encode string
     * @param data
     * @return base64 of [data]
     */
    public String toBase64(String data) {
        return Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));
    }

}
