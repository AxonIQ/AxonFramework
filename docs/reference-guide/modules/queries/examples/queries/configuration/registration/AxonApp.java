package queries.configuration.registration;

// tag::query-configuration-api[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.common.configuration.AxonConfiguration;

public class AxonApp {

    public static void main(String[] args) {
        QueryHandlingModule cardSummaryModule =
                QueryHandlingModule.named("card-summary-projection")
                                   .queryHandlers()
                                   .autodetectedQueryHandlingComponent(config -> new CardSummaryProjection())
                                   .build();
        MessagingConfigurer configurer =
            MessagingConfigurer.create()
                               .registerQueryHandlingModule(cardSummaryModule);
        // Build and start the configuration
        AxonConfiguration configuration = configurer.build();
        configuration.start();
    }
}
// end::query-configuration-api[]
