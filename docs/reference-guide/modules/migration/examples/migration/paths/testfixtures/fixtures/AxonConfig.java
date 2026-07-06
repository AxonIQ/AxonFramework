package migration.paths.testfixtures.fixtures;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

/**
 * Shared production configuration reused across the test-fixtures.adoc samples that only reference
 * {@code AxonConfig.appConfigurer()} without repeating its definition. Not shown in the rendered documentation.
 */
public class AxonConfig {

    public static ApplicationConfigurer appConfigurer() {
        return EventSourcingConfigurer.create()
                                      .registerEntity(EventSourcedEntityModule.autodetected(
                                              String.class, GiftCard.class
                                      ));
    }
}
