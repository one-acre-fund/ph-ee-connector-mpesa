package org.mifos.connector.mpesa.camel.config;

import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Hardens Camel's Apache HttpClient pool against Azure NAT Gateway / LB SNAT idle drops.
 * Those middleboxes silently drop mappings after ~4 minutes; reusing the dead keep-alive
 * socket then surfaces as {@code java.net.SocketException: Connection reset}.
 */
@Configuration
public class HttpClientConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientConfiguration.class);

    @Value("${mpesa.api.connection-time-to-live:120000}")
    private long connectionTimeToLiveMs;

    @Value("${mpesa.api.connection-idle-evict:60000}")
    private long connectionIdleEvictMs;

    @Value("${mpesa.api.validate-after-inactivity:1000}")
    private int validateAfterInactivityMs;

    @Value("${mpesa.api.max-total-connections:200}")
    private int maxTotalConnections;

    @Value("${mpesa.api.connections-per-route:50}")
    private int connectionsPerRoute;

    @Bean
    CamelContextConfiguration httpClientContextConfiguration() {
        return new CamelContextConfiguration() {
            @Override
            public void beforeApplicationStart(CamelContext camelContext) {
                PoolingHttpClientConnectionManager connectionManager = createConnectionManager();
                configureComponent(camelContext.getComponent("http", HttpComponent.class), connectionManager);
                configureComponent(camelContext.getComponent("https", HttpComponent.class), connectionManager);
                logger.info(
                        "Configured Camel HTTP client pool: ttl={}ms, idleEvict={}ms, validateAfterInactivity={}ms",
                        connectionTimeToLiveMs, connectionIdleEvictMs, validateAfterInactivityMs);
            }

            @Override
            public void afterApplicationStart(CamelContext camelContext) {
                // no-op
            }
        };
    }

    private void configureComponent(HttpComponent component, PoolingHttpClientConnectionManager connectionManager) {
        if (component == null) {
            return;
        }
        component.setConnectionTimeToLive(connectionTimeToLiveMs);
        component.setClientConnectionManager(connectionManager);
        component.setMaxTotalConnections(maxTotalConnections);
        component.setConnectionsPerRoute(connectionsPerRoute);
        component.setHttpClientConfigurer(clientBuilder -> {
            clientBuilder.evictExpiredConnections();
            clientBuilder.evictIdleConnections(connectionIdleEvictMs, TimeUnit.MILLISECONDS);
        });
    }

    PoolingHttpClientConnectionManager createConnectionManager() {
        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", new SSLConnectionSocketFactory(SSLContexts.createDefault()))
                .build();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
                registry, null, null, null, connectionTimeToLiveMs, TimeUnit.MILLISECONDS);
        connectionManager.setValidateAfterInactivity(validateAfterInactivityMs);
        connectionManager.setMaxTotal(maxTotalConnections);
        connectionManager.setDefaultMaxPerRoute(connectionsPerRoute);
        return connectionManager;
    }
}
