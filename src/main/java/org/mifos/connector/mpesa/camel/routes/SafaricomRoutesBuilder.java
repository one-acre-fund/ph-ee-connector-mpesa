package org.mifos.connector.mpesa.camel.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.util.json.JsonObject;
import org.json.JSONObject;
import org.mifos.connector.mpesa.auth.AccessTokenStore;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.dto.StkCallback;
import org.mifos.connector.mpesa.dto.TransactionStatusRequestDTO;
import org.mifos.connector.mpesa.flowcomponents.CorrelationIDStore;
import org.mifos.connector.mpesa.flowcomponents.mpesa.MpesaGenericProcessor;
import org.mifos.connector.mpesa.flowcomponents.transaction.CollectionResponseProcessor;
import org.mifos.connector.mpesa.flowcomponents.transaction.TransactionResponseProcessor;
import org.mifos.connector.mpesa.utility.ConnectionUtils;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.*;
import static org.mifos.connector.mpesa.safaricom.config.SafaricomProperties.MPESA_BUY_GOODS_TRANSACTION_TYPE;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.*;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;


@Component
public class SafaricomRoutesBuilder extends RouteBuilder {



    @Value("${mpesa.api.lipana}")
    private String buyGoodsLipanaUrl;

    @Value("${mpesa.api.transaction-status}")
    private String transactionStatusUrl;

    @Value("${mpesa.max-retry-count}")
    private Integer maxRetryCount;

    @Value("${mpesa.api.timeout}")
    private Integer mpesaTimeout;

    private final ObjectMapper objectMapper;

    private final CollectionResponseProcessor collectionResponseProcessor;

    private final TransactionResponseProcessor transactionResponseProcessor;

    private final MpesaGenericProcessor mpesaGenericProcessor;

    private final AccessTokenStore accessTokenStore;

    private final CorrelationIDStore correlationIDStore;

    private final SafaricomUtils safaricomUtils;

    private final MpesaUtils mpesaUtils;

    private MpesaProps.MPESA mpesaProps;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public SafaricomRoutesBuilder(ObjectMapper objectMapper, CollectionResponseProcessor collectionResponseProcessor,
                                  TransactionResponseProcessor transactionResponseProcessor,
                                  MpesaGenericProcessor mpesaGenericProcessor,
                                  AccessTokenStore accessTokenStore, CorrelationIDStore correlationIDStore, SafaricomUtils safaricomUtils,
                                  MpesaUtils mpesaUtils) {
        this.objectMapper = objectMapper;
        this.collectionResponseProcessor = collectionResponseProcessor;
        this.transactionResponseProcessor = transactionResponseProcessor;
        this.mpesaGenericProcessor = mpesaGenericProcessor;
        this.accessTokenStore = accessTokenStore;
        this.correlationIDStore = correlationIDStore;
        this.safaricomUtils = safaricomUtils;
        this.mpesaUtils = mpesaUtils;
    }

    @Override
    public void configure() {
        mpesaProps = mpesaUtils.setMpesaProperties();
        logger.info("AMS Name set by configure" + mpesaProps.getName());
        /*
         * Use this endpoint for getting the mpesa transaction status
         * The request parameter is same as the safaricom standards
         */

        from("rest:POST:/buygoods/transactionstatus")
                .id("buy-goods-transaction-status")
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    TransactionStatusRequestDTO transactionStatusRequestDTO = objectMapper.readValue(
                            body, TransactionStatusRequestDTO.class);

                    exchange.setProperty(BUY_GOODS_TRANSACTION_STATUS_BODY, transactionStatusRequestDTO);
                    logger.info(body);
                })
                .to("direct:lipana-transaction-status");


        /*
           Use this endpoint for receiving the callback form safaricom mpesa endpoint
         */
        from("rest:POST:/buygoods/callback")
                .id("buy-goods-callback")
                .setProperty(CALLBACK_DATETIME, simple(Instant.now().toString()))
                .log(LoggingLevel.INFO, "Callback body \n\n..\n\n..\n\n.. ${body}")
                .unmarshal().json(JsonLibrary.Jackson, JsonObject.class)
                .to("direct:callback-handler");


        from("direct:callback-handler")
                .id("callback-handler")
                .log(LoggingLevel.INFO, "Handling callback body")
                .process(exchange -> {
                    JsonObject response = exchange.getIn().getBody(JsonObject.class);
                    StkCallback callback = SafaricomUtils.getStkCallback(response);
                    String checkoutRequestId = callback.getCheckoutRequestId();
                    String clientCorrelationId = correlationIDStore.getClientCorrelation(checkoutRequestId);
                    logger.info("Callback for checkout ID {} received on {} with request body {}. Correlation ID found: {}", callback.getCheckoutRequestId(),
                        exchange.getProperty(CALLBACK_DATETIME), exchange.getIn().getBody(String.class), clientCorrelationId);
                    exchange.setProperty(TRANSACTION_ID, clientCorrelationId);
                    exchange.setProperty(SERVER_TRANSACTION_ID, checkoutRequestId);
                    logger.info("\n\n StkCallback " + callback + "\n");
                    logger.info("\n\n Correlation Key " + clientCorrelationId +"\n\n" );
                    if(callback.getResultCode() == 0) {
                        exchange.setProperty(TRANSACTION_FAILED, false);
                        exchange.setProperty(SERVER_TRANSACTION_RECEIPT_NUMBER, SafaricomUtils.getTransactionId(response));
                        exchange.setProperty(CALLBACK_RECEIVED, true);
                        exchange.setProperty(CALLBACK, callback.toString());
                    } else {
                        exchange.setProperty(ERROR_CODE, callback.getResultCode().toString());
                        exchange.setProperty(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
                        exchange.setProperty(ERROR_DESCRIPTION, callback.getResultDesc());
                    }
                })
                .choice()
                .when(exchangeProperty(ERROR_CODE).isNotNull())
                .to("direct:filter-by-error-code")
                .choice()
                .when(exchangeProperty(IS_ERROR_RECOVERABLE).isEqualTo(false))
                .process(exchange -> exchange.setProperty(TRANSACTION_FAILED, true))
                .process(collectionResponseProcessor)
                .otherwise()
                .log("Current resultCode is recoverable hence waiting for getting transaction status")
                .endChoice()
                .otherwise()
                .process(collectionResponseProcessor);

        /*
          Rest endpoint to initiate payment for buy goods

          Sample request body: {
              "BusinessShortCode": 174379,
              "Amount": 1,
              "PartyA": 254708374149,
              "PartyB": 174379,
              "PhoneNumber": 254708374149,
              "CallBackURL": "https://mydomain.com/path",
              "AccountReference": "CompanyXLTD",
              "TransactionDesc": "Payment of X"
            }
         */
        from("rest:POST:/buygoods")
                .id("buy-goods-online")
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO = objectMapper.readValue(
                            body, BuyGoodsPaymentRequestDTO.class);

                    exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsPaymentRequestDTO);
                    logger.info(buyGoodsPaymentRequestDTO.toString());

                })
                .to("direct:buy-goods-base");

        /*
         * Starts the payment flow
         *
         * Step1: Authenticate the user by initiating [get-access-token] flow
         * Step2: On successful [Step1], directs to [lipana-buy-goods] flow
         */
        from("direct:buy-goods-base")
                .id("buy-goods-base")
                .log(LoggingLevel.INFO, "Starting buy goods flow for transaction ${exchangeProperty." + CORRELATION_ID + "} with max retry count: " + maxRetryCount)
                .to("direct:get-access-token")
                .process(exchange -> exchange.setProperty(ACCESS_TOKEN, accessTokenStore.getAccessToken()))
                .log(LoggingLevel.INFO, "Got access token for transaction ${exchangeProperty." + CORRELATION_ID + "}, moving on to buy goods API call.")
                .to("direct:lipana-buy-goods")
                .log(LoggingLevel.INFO, "Received buy goods API response for transaction ${exchangeProperty." + CORRELATION_ID + "} on ${header.Date} with status: ${header.CamelHttpResponseCode}")
                .log(LoggingLevel.INFO, "Buy goods API response body for transaction ${exchangeProperty." + CORRELATION_ID + "}: ${body}")
                .to("direct:transaction-response-handler");

        /*
         * Starts the payment flow
         *
         * Step1: Authenticate the user by initiating [get-access-token] flow
         * Step2: On successful [Step1], directs to [lipana-buy-goods] flow
         */
        from("direct:get-transaction-status-base")
                .id("buy-goods-get-transaction-status-base")
                .log(LoggingLevel.INFO, "Starting buy goods transaction status flow for transaction ${exchangeProperty." + TRANSACTION_ID + "}")
                .choice()
                .when(exchangeProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT).isLessThanOrEqualTo(maxRetryCount))
                .to("direct:get-access-token")
                .process(exchange -> exchange.setProperty(ACCESS_TOKEN, accessTokenStore.getAccessToken()))
                .log(LoggingLevel.INFO, "Got access token for transaction ${exchangeProperty." + TRANSACTION_ID + "}, moving on to transaction status API call.")
                .to("direct:lipana-transaction-status")
                .log(LoggingLevel.INFO, "Received status enquiry API response for transaction ${exchangeProperty." + TRANSACTION_ID + "} on ${header.Date} with response status: ${header.CamelHttpResponseCode}")
                .log(LoggingLevel.INFO, "Transaction status API response body for transaction ${exchangeProperty." + TRANSACTION_ID + "}: ${body}")
                .to("direct:transaction-status-response-handler")
                .otherwise()
                .process(exchange -> {
                    exchange.setProperty(IS_RETRY_EXCEEDED, true);
                    exchange.setProperty(TRANSACTION_FAILED, true);
                })
                .process(collectionResponseProcessor);

        /*
         * Route to handle async transaction status API responses
         */
        from("direct:transaction-status-response-handler")
                .id("transaction-status-response-handler")
                .log(LoggingLevel.INFO, "## Staring transaction status handler route")
                .choice()
                .when(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo("200"))
                .log(LoggingLevel.INFO, "Transaction status request successful")
                .process(exchange -> {
                    String body = exchange.getIn().getBody(String.class);
                    JSONObject jsonObject = new JSONObject(body);
                    exchange.setProperty(LAST_RESPONSE_BODY, body);
                    String server_id = jsonObject.getString("CheckoutRequestID");
                    String resultCode = jsonObject.getString("ResultCode");
                    String resultDescription = jsonObject.getString("ResultDesc");
                    String receiptNumber = jsonObject.getString(MPESA_RECEIPT_NUMBER);
                    Object correlationId = exchange.getProperty(CORRELATION_ID);

                    if(resultCode.equals("0")) {
                        exchange.setProperty(TRANSACTION_FAILED, false);
                        exchange.setProperty(SERVER_TRANSACTION_RECEIPT_NUMBER, receiptNumber);
                    } else {
                        exchange.setProperty(ERROR_CODE, resultCode);
                        exchange.setProperty(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
                        exchange.setProperty(ERROR_DESCRIPTION, resultDescription);
                    }
                    exchange.setProperty(SERVER_TRANSACTION_ID, server_id);
                    exchange.setProperty(TRANSACTION_ID, correlationId);
                })
                .choice()
                .when(exchange -> exchange.getProperty(ERROR_CODE) != null)
                .to("direct:filter-by-error-code")
                .process(exchange -> {
                    boolean isRecoverableError = exchange.getProperty(IS_ERROR_RECOVERABLE, Boolean.class);
                    if(isRecoverableError) {
                        exchange.setProperty(IS_TRANSACTION_PENDING, true);
                    } else {
                        exchange.setProperty(TRANSACTION_FAILED, true);
                    }
                })
                .endChoice()
                .process(collectionResponseProcessor)
                .when(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo("500"))
                .process(exchange -> {
                    logger.info("Handling 500 transaction status case");
                    String body = exchange.getIn().getBody(String.class);
                    JSONObject jsonObject = new JSONObject(body);
                    exchange.setProperty(LAST_RESPONSE_BODY, body);
                    String errorCode = jsonObject.getString("errorCode");
                    String errorDescription = jsonObject.getString("errorMessage");
                    exchange.setProperty(ERROR_CODE, errorCode);
                    exchange.setProperty(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
                    exchange.setProperty(ERROR_DESCRIPTION, errorDescription);
                    Object correlationId = exchange.getProperty(CORRELATION_ID);
                    exchange.setProperty(TRANSACTION_ID, correlationId);
                    exchange.setProperty(IS_TRANSACTION_PENDING, true);
                })
                .process(collectionResponseProcessor)
                .otherwise()
                .log(LoggingLevel.ERROR, "Transaction status request unsuccessful")
                .process(exchange -> {
                    Object correlationId = exchange.getProperty(CORRELATION_ID);
                    exchange.setProperty(TRANSACTION_ID, correlationId);
                })
                .setProperty(TRANSACTION_FAILED, constant(true))
                .process(collectionResponseProcessor);

        /*
         * Route to handle async API responses
         */
        from("direct:transaction-response-handler")
                .id("transaction-response-handler")
                .choice()
                .when(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo("200"))
                .log(LoggingLevel.INFO, "Collection request successful")
                .process(exchange -> {

                    JSONObject jsonObject = new JSONObject(exchange.getIn().getBody(String.class));
                    String server_id = jsonObject.getString("CheckoutRequestID");
                    Object correlationId = exchange.getProperty(CORRELATION_ID);

                    exchange.setProperty(SERVER_TRANSACTION_ID, server_id);
                    exchange.setProperty(TRANSACTION_ID, correlationId);

                    correlationIDStore.addMapping(server_id, (String) correlationId);
                    logger.info("Saved correlationId mapping \n\n {" + server_id + " : " + correlationId + "}");

                })
                .process(transactionResponseProcessor)
                .otherwise()
                .log(LoggingLevel.ERROR, "Collection request unsuccessful")
                .process(exchange -> {
                    Object correlationId = exchange.getProperty(CORRELATION_ID);
                    exchange.setProperty(TRANSACTION_ID, correlationId);
                })
                .setProperty(TRANSACTION_FAILED, constant(true))
                .process(transactionResponseProcessor);

        /*
         * Takes the access toke and payment request and forwards the requests to lipana API.
         * [Password] and [TransactionType] are set in runtime and request is forwarded to lipana endpoint.
         */
        from("direct:lipana-buy-goods")
                .removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("Authorization", simple("Bearer ${exchangeProperty."+ACCESS_TOKEN+"}"))
                .setBody(exchange -> {
                    mpesaProps = mpesaUtils.setMpesaProperties();
                    logger.info("MPESA properties for transaction with id {}: AMS - {}, Shortcode - {}, TILL - {}",
                        exchange.getProperty(CORRELATION_ID), mpesaProps.getName(), mpesaProps.getBusinessShortCode(), mpesaProps.getTill());
                    BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO =
                            (BuyGoodsPaymentRequestDTO) exchange.getProperty(BUY_GOODS_REQUEST_BODY);

                    String password = safaricomUtils.getPassword("" + buyGoodsPaymentRequestDTO.getBusinessShortCode(),
                            mpesaProps.getPassKey(),
                            "" + buyGoodsPaymentRequestDTO.getTimestamp());

                    buyGoodsPaymentRequestDTO.setPassword(password);
                    buyGoodsPaymentRequestDTO.setTransactionType(MPESA_BUY_GOODS_TRANSACTION_TYPE);

                    logger.info("Buy goods request body for transaction with id {} on {}: \n\n..\n\n..\n\n.. {}", exchange.getProperty(CORRELATION_ID),
                        Instant.now(), buyGoodsPaymentRequestDTO);
                    logger.info(MpesaUtils.maskString(accessTokenStore.getAccessToken()));

                    return buyGoodsPaymentRequestDTO;
                })
                .marshal().json(JsonLibrary.Jackson)
                .toD(mpesaProps.getApiHost() + buyGoodsLipanaUrl +"?bridgeEndpoint=true&throwExceptionOnFailure=false&" +
                        ConnectionUtils.getConnectionTimeoutDsl(mpesaTimeout))
                .process(mpesaGenericProcessor)
                .log(LoggingLevel.INFO, "MPESA API called, response: \n\n ${body}");

        /*
         * Takes the request for transaction status and forwards in to the lipana transaction status endpoint
         */
        from("direct:lipana-transaction-status")
                .removeHeader("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader("Authorization", simple("Bearer ${exchangeProperty."+ACCESS_TOKEN+"}"))
                .setBody(exchange -> {
                    TransactionStatusRequestDTO transactionStatusRequestDTO = new TransactionStatusRequestDTO();

                    BuyGoodsPaymentRequestDTO buyGoodsPaymentRequestDTO =
                            (BuyGoodsPaymentRequestDTO) exchange.getProperty(BUY_GOODS_REQUEST_BODY);

                    logger.info("BUY GOODS REQUEST: \n\n..\n\n..\n\n.." + buyGoodsPaymentRequestDTO);

                    transactionStatusRequestDTO.setBusinessShortCode(buyGoodsPaymentRequestDTO.getBusinessShortCode());
                    transactionStatusRequestDTO.setTimestamp(""+safaricomUtils.getTimestamp());
                    transactionStatusRequestDTO.setCheckoutRequestId(
                            exchange.getProperty(SERVER_TRANSACTION_ID, String.class));
                    transactionStatusRequestDTO.setPassword(safaricomUtils.getPassword(
                            "" + transactionStatusRequestDTO.getBusinessShortCode(),
                            mpesaProps.getPassKey(), transactionStatusRequestDTO.getTimestamp()
                    ));


                    logger.info("Transaction status request DTO for transaction with id {} sent on {} \n\n..\n\n..\n\n.. {}", 
                        exchange.getProperty(TRANSACTION_ID), Instant.now(), transactionStatusRequestDTO);
                    return  transactionStatusRequestDTO;
                })
                .marshal().json(JsonLibrary.Jackson)
                .toD(mpesaProps.getApiHost() + transactionStatusUrl +"?bridgeEndpoint=true&throwExceptionOnFailure=false&"+
                        ConnectionUtils.getConnectionTimeoutDsl(mpesaTimeout))
                .process(mpesaGenericProcessor)
                .log(LoggingLevel.INFO, "MPESA STATUS called, response: \n\n ${body}");
    }
}
