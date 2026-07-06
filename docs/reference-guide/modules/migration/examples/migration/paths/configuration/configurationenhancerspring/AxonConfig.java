package migration.paths.configuration.configurationenhancerspring;

// tag::configuration-enhancer-spring[]
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AxonConfig {

    @Bean
    public ConfigurationEnhancer myEnhancer() {
        return registry -> registry.registerComponent(
                MyService.class,
                config -> new MyService()
        );
    }
}
// end::configuration-enhancer-spring[]

class MyService {
}
