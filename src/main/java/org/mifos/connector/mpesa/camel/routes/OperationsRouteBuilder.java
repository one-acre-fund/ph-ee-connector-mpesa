package org.mifos.connector.mpesa.camel.routes;

import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.LoggingLevel;
import org.json.JSONObject;
import org.mifos.connector.mpesa.zeebe.ZeebeCommandHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mpesa.zeebe.ZeebeMessages.OPERATOR_MANUAL_RECOVERY;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;

@Component
public class OperationsRouteBuilder extends ErrorHandlerRouteBuilder {

    @Autowired
    private ZeebeClient zeebeClient;

    @Value("${zeebe.client.request-timeout}")
    private Duration requestTimeout;

    @Override
    public void configure() {

        from("rest:POST:/channel/transaction/{" + TRANSACTION_ID + "}/resolve")
                .id("transaction-resolve")
                .log(LoggingLevel.INFO, "## operator transaction resolve")
                .process(e -> {
                    Map<String, Object> variables = new HashMap<>();
                    JSONObject request = new JSONObject(e.getIn().getBody(String.class));
                    request.keys().forEachRemaining(k -> {
                        variables.put(k, request.get(k));
                    });

                    ZeebeCommandHelper.join(zeebeClient.newPublishMessageCommand()
                            .messageName(OPERATOR_MANUAL_RECOVERY)
                            .correlationKey(e.getIn().getHeader(TRANSACTION_ID, String.class))
                            .timeToLive(Duration.ofMillis(30000))
                            .variables(variables)
                            .send(), requestTimeout);
                })
                .setBody(constant((Object) null));

        from("rest:POST:/channel/job/resolve")
                .id("job-resolve")
                .log(LoggingLevel.INFO, "## operator job resolve")
                .process(e -> {
                    JSONObject request = new JSONObject(e.getIn().getBody(String.class));
                    JSONObject incident = request.getJSONObject("incident");
                    Map<String, Object> newVariables = new HashMap<>();
                    JSONObject requestedVariables = request.getJSONObject("variables");
                    requestedVariables.keys().forEachRemaining(k -> {
                        newVariables.put(k, requestedVariables.get(k));
                    });

                    ZeebeCommandHelper.join(zeebeClient.newSetVariablesCommand(incident.getLong("elementInstanceKey"))
                            .variables(newVariables)
                            .send(), requestTimeout);

                    ZeebeCommandHelper.join(zeebeClient.newUpdateRetriesCommand(incident.getLong("jobKey"))
                            .retries(incident.getInt("newRetries"))
                            .send(), requestTimeout);

                    ZeebeCommandHelper.join(zeebeClient.newResolveIncidentCommand(incident.getLong("key"))
                            .send(), requestTimeout);
                })
                .setBody(constant((Object) null));

        from("rest:POST:/channel/workflow/resolve")
                .id("workflow-resolve")
                .log(LoggingLevel.INFO, "## operator workflow resolve")
                .process(e -> {
                    JSONObject request = new JSONObject(e.getIn().getBody(String.class));
                    JSONObject incident = request.getJSONObject("incident");
                    Map<String, Object> newVariables = new HashMap<>();
                    JSONObject requestedVariables = request.getJSONObject("variables");
                    requestedVariables.keys().forEachRemaining(k -> {
                        newVariables.put(k, requestedVariables.get(k));
                    });

                    ZeebeCommandHelper.join(zeebeClient.newSetVariablesCommand(incident.getLong("elementInstanceKey"))
                            .variables(newVariables)
                            .send(), requestTimeout);

                    ZeebeCommandHelper.join(zeebeClient.newResolveIncidentCommand(incident.getLong("key"))
                            .send(), requestTimeout);
                })
                .setBody(constant((Object) null));

        from("rest:POST:/channel/workflow/{workflowInstanceKey}/cancel")
                .id("workflow-cancel")
                .log(LoggingLevel.INFO, "## operator workflow cancel ${header.workflowInstanceKey}")
                .process(e -> ZeebeCommandHelper.join(
                        zeebeClient.newCancelInstanceCommand(
                                        Long.parseLong(e.getIn().getHeader("workflowInstanceKey", String.class)))
                                .send(), requestTimeout))
                .setBody(constant((Object) null));

    }
}
