package org.mifos.connector.mpesa.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.camel.ProducerTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Builds a real {@link MpesaHttpCaller} for route tests.
 */
public final class TestCallers {

    private TestCallers() {
    }

    public static MpesaHttpCaller caller(ProducerTemplate producerTemplate) {
        return caller(producerTemplate, new MpesaResilienceProperties());
    }

    public static MpesaHttpCaller caller(ProducerTemplate producerTemplate, MpesaResilienceProperties properties) {
        return new MpesaHttpCaller(producerTemplate, properties,
                new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class));
    }
}
