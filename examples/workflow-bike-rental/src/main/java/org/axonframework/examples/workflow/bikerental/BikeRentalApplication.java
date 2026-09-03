/*
 * Copyright (c) 2010-2026. AxonIQ B.V.
 *
 * Licensed under the AXONIQ TERMS OF SERVICE,
 * Version 29 April 2026 (the "License");
 *
 * The software is available for evaluation use without registration.
 * Continued use beyond the evaluation period requires registration
 * and a commercial license. See the License for the specific language
 * governing permissions and limitations under the License.
 * You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *  https://www.axoniq.io/legal/terms-of-service
 *
 * For licensing information and to register, visit:
 *  https://www.axoniq.io/pricing
 */
package org.axonframework.examples.workflow.bikerental;

import jakarta.persistence.EntityManagerFactory;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Main class of the application.
 * @since 5.4.0
 */
@SpringBootApplication
public class BikeRentalApplication {

    /**
     * Use JPA Token store as a default token store for projections.
     *
     * @param converter            messenger converter.
     * @param entityManagerFactory entity manager factory.
     * @return JPA Token store.
     */
    @Bean
    @Primary
    public TokenStore tokenStore(
            GeneralConverter converter,
            EntityManagerFactory entityManagerFactory) {
        return new JpaTokenStore(
                new JpaTransactionalExecutorProvider(entityManagerFactory),
                converter,
                JpaTokenStoreConfiguration.DEFAULT
        );
    }

    /**
     * Main method of the application.
     *
     * @param args arguments to pass to main method.
     */
    public static void main(String[] args) {
        SpringApplication.run(BikeRentalApplication.class, args);
    }
}
