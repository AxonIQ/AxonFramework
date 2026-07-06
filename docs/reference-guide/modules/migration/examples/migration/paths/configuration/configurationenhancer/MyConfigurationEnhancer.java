package migration.paths.configuration.configurationenhancer;

// tag::configuration-enhancer[]
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;

public class MyConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerComponent(MyService.class, config -> new MyService());
    }
}
// end::configuration-enhancer[]

class MyService {
}
