package monitoring.metrics.micrometer.metricregistry;

// tag::micrometer-metric-registry[]
import io.micrometer.core.instrument.MeterRegistry;
import org.axonframework.extension.metrics.micrometer.MetricsConfigurationEnhancer;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

class MetricsConfiguration {

    public void configureDefaultMetrics(MessagingConfigurer configurer,
                                        MeterRegistry meterRegistry) {
        configurer.componentRegistry(componentRegistry -> componentRegistry.registerEnhancer(
                new MetricsConfigurationEnhancer(meterRegistry)
        ));
    }
}
// end::micrometer-metric-registry[]
