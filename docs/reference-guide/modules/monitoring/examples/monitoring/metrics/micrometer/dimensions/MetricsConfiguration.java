package monitoring.metrics.micrometer.dimensions;

// tag::micrometer-dimensions-toggle[]
import io.micrometer.core.instrument.MeterRegistry;
import org.axonframework.extension.metrics.micrometer.MetricsConfigurationEnhancer;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

class MetricsConfiguration {

    public void configureDefaultMetrics(MessagingConfigurer configurer,
                                        MeterRegistry meterRegistry) {
        boolean disableDimensions = false;
        configurer.componentRegistry(componentRegistry -> componentRegistry.registerEnhancer(
                new MetricsConfigurationEnhancer(meterRegistry, disableDimensions)
        ));
    }
}
// end::micrometer-dimensions-toggle[]
