package queries.querydispatchers;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

class QueryConfigurationExample {

    void configureQueryInfrastructure() {
        // tag::query-configuration-api[]
        MessagingConfigurer configurer = MessagingConfigurer.create();

        // QueryBus is registered by default, but can be customized
        configurer.registerQueryBus(config -> new SimpleQueryBus(config.getComponent(UnitOfWorkFactory.class)));

        Configuration configuration = configurer.start();
        QueryGateway queryGateway = configuration.getComponent(QueryGateway.class);
        QueryBus queryBus = configuration.getComponent(QueryBus.class);
        // end::query-configuration-api[]
    }
}
