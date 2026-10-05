package org.mifos.connector.mpesa.camel.routes;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.ListJacksonDataFormat;
import org.mifos.connector.mpesa.dto.ErrorCode;
import org.mifos.connector.mpesa.flowcomponents.ErrorCodeCache;
import org.mifos.connector.mpesa.flowcomponents.transaction.ErrorProcessor;
import org.mifos.connector.mpesa.utility.ConnectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.*;
import static org.mifos.connector.mpesa.camel.config.OperationsProperties.FILTER_BY_ERROR_CODE;

@Component
public class OperationsRoute extends RouteBuilder {

    @Value("${operations.host}")
    private String operationsHost;

    @Value("${operations.base-url}")
    private String operationsBaseUrl;

    @Value("${operations.filter-path}")
    private String operationsFilterPath;

    @Value("${operations.timeout:60000}")
    private Integer operationsTimeout = 60000;

    @Value("${tenant}")
    private String tenantId;

    @Autowired
    private ErrorProcessor errorProcessor;

    @Autowired
    private ErrorCodeCache errorCodeCache;

    @Override
    public void configure() {

        from("rest:get:test/filter")
                .id("filter-test")
                .process(exchange -> {
                    exchange.setProperty(ERROR_CODE, "1037");
                    exchange.setProperty("tenantId", "oaf");
                })
                .to("direct:filter-by-error-code")
                .process(exchange -> {
                    boolean isRe = exchange.getProperty(IS_ERROR_RECOVERABLE, Boolean.class);
                    exchange.getIn().setBody(""+isRe);
                });

        from("direct:filter-by-error-code")
                .id("filter-by-error-codes")
                .log(LoggingLevel.INFO, "### Starting FILTER-BY-ERROR-CODE route")
                // The lookup only sets IS_ERROR_RECOVERABLE; keep the caller's message (e.g. the callback
                // being answered, or the M-Pesa status response) from being replaced by the operations response
                .process(exchange -> {
                    exchange.setProperty(FILTER_ORIGINAL_HEADERS, new HashMap<>(exchange.getIn().getHeaders()));
                    exchange.setProperty(FILTER_ORIGINAL_BODY, exchange.getIn().getBody());
                })
                .process(exchange -> exchange.setProperty(CACHED_ERROR_CODE,
                        errorCodeCache.get(exchange.getProperty(ERROR_CODE, String.class))))
                .choice()
                .when(exchange -> errorCodeCache.isFresh(
                        exchange.getProperty(CACHED_ERROR_CODE, ErrorCodeCache.Entry.class)))
                    .process(exchange -> exchange.setProperty(IS_ERROR_RECOVERABLE,
                            exchange.getProperty(CACHED_ERROR_CODE, ErrorCodeCache.Entry.class).recoverable()))
                    .log(LoggingLevel.INFO, "Error code ${exchangeProperty." + ERROR_CODE
                            + "} resolved from cache, recoverable: ${exchangeProperty." + IS_ERROR_RECOVERABLE + "}")
                .otherwise()
                    .to("direct:fetch-error-code-from-operations")
                .end()
                .process(this::restoreOriginalMessage);

        from("direct:fetch-error-code-from-operations")
                .id("fetch-error-code-from-operations")
                .removeHeader("*")
                .removeHeader("Authorization")
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader(TENANT_ID, constant(tenantId))
                .setHeader(Exchange.HTTP_RAW_QUERY,
                        simple("by=" + FILTER_BY_ERROR_CODE + "&value=${exchangeProperty." + ERROR_CODE + "}"))
                .doTry()
                    .toD(getFilterUrl())
                    .log(LoggingLevel.INFO, "Status: ${header.CamelHttpResponseCode}")
                    .log(LoggingLevel.INFO, "Operations response: \n\n.. ${body}")
                    .to("direct:filter-response-handler")
                .doCatch(Exception.class)
                    .log(LoggingLevel.WARN, "Operations filter call failed for error code ${exchangeProperty."
                            + ERROR_CODE + "}: ${exception.message}")
                    .process(this::applyFallback)
                .end();

        from("direct:get-recoverable-error-codes")
                .id("get-recoverable-error-codes")
                .log(LoggingLevel.INFO, "### Starting GET-RECOVERABLE-CODES route")
                .removeHeader("*")
                .removeHeader("Authorization")
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader(TENANT_ID, constant(tenantId))
                .setHeader(Exchange.HTTP_RAW_QUERY,
                        simple("by=" + FILTER_BY_ERROR_CODE + "&value=${exchangeProperty." + ERROR_CODE + "}"))
                .toD(getFilterUrl())
                .log(LoggingLevel.INFO, "Operations response: \n\n.. ${body}")
                .to("direct:filter-response-handler");

        from("direct:get-non-recoverable-error-codes")
                .id("get-non-recoverable-error-codes")
                .log(LoggingLevel.INFO, "### Starting GET-NON-RECOVERABLE-CODES route")
                .removeHeader("*")
                .removeHeader("Authorization")
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .setHeader("Content-Type", constant("application/json"))
                .setHeader(TENANT_ID, constant(tenantId))
                .setHeader(Exchange.HTTP_RAW_QUERY,
                        simple("by=" + FILTER_BY_ERROR_CODE + "&value=${exchangeProperty." + ERROR_CODE + "}"))
                .toD(getFilterUrl())
                .log(LoggingLevel.INFO, "Operations response: \n\n.. ${body}")
                .to("direct:filter-response-handler");

        from("direct:filter-response-handler")
                .id("filter-response-handler")
                .log(LoggingLevel.INFO, "### Starting FILTER-RESPONSE-HANDLER route")
                .choice()
                .when(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo("200"))
                    .unmarshal(new ListJacksonDataFormat(ErrorCode.class))
                    .process(errorProcessor)
                    .process(exchange -> {
                        Boolean recoverable = exchange.getProperty(IS_ERROR_RECOVERABLE, Boolean.class);
                        if (recoverable != null) {
                            errorCodeCache.put(exchange.getProperty(ERROR_CODE, String.class), recoverable);
                        }
                    })
                .otherwise()
                    .log(LoggingLevel.WARN, "Operations filter call failed with status ${header.CamelHttpResponseCode} "
                            + "for error code ${exchangeProperty." + ERROR_CODE + "}")
                    .process(this::applyFallback);

    }

    @SuppressWarnings("unchecked")
    private void restoreOriginalMessage(Exchange exchange) {
        exchange.getIn().setHeaders((Map<String, Object>) exchange.removeProperty(FILTER_ORIGINAL_HEADERS));
        exchange.getIn().setBody(exchange.removeProperty(FILTER_ORIGINAL_BODY));
    }

    /**
     * Operations couldn't answer: use the last known (stale) cached value if there is one,
     * otherwise default to non-recoverable.
     */
    private void applyFallback(Exchange exchange) {
        ErrorCodeCache.Entry cached = exchange.getProperty(CACHED_ERROR_CODE, ErrorCodeCache.Entry.class);
        if (cached != null) {
            log.warn("Using stale cached value for error code {}, recoverable: {}",
                    exchange.getProperty(ERROR_CODE), cached.recoverable());
            exchange.setProperty(IS_ERROR_RECOVERABLE, cached.recoverable());
        } else {
            log.warn("No cached value for error code {}, treating as non-recoverable", exchange.getProperty(ERROR_CODE));
            exchange.setProperty(IS_ERROR_RECOVERABLE, false);
        }
    }

    private String getFilterUrl() {
        String url = operationsHost + operationsBaseUrl + operationsFilterPath;
        return url + "?bridgeEndpoint=true&throwExceptionOnFailure=false&"
                + ConnectionUtils.getConnectionTimeoutDsl(operationsTimeout);
    }
}
