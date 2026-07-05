package monitoring.metrics.dropwizard.springboot;

// tag::dropwizard-springboot-metric-registry[]
import io.dropwizard.metrics5.MetricRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MetricsConfiguration {

    @Bean
    public MetricRegistry metricRegistry() {
        return new MetricRegistry();
    }
}
// end::dropwizard-springboot-metric-registry[]
