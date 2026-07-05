package monitoring.metrics.dropwizard.configurationapi;

// tag::dropwizard-configuration-api[]
import io.dropwizard.metrics5.MetricRegistry;
import org.axonframework.extension.metrics.dropwizard.MetricsConfigurationEnhancer;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;

class MetricsConfiguration {

    public void configureDefaultMetrics(MessagingConfigurer configurer,
                                        MetricRegistry metricRegistry) {
        configurer.componentRegistry(componentRegistry -> componentRegistry.registerEnhancer(
                new MetricsConfigurationEnhancer(metricRegistry)
        ));
    }
}
// end::dropwizard-configuration-api[]
