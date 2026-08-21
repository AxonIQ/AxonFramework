/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.examples.sagarecipes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the saga recipes example.
 * <p>
 * The application hosts two bounded contexts, {@code rental} and {@code payment}, and a {@code saga} package that
 * integrates them. The rental payment process is implemented several times over; exactly one implementation is active
 * at a time, selected through the {@code saga.recipe} property.
 * <p>
 * Scheduling is enabled because the deadline replacement in {@code saga.deadline} sweeps overdue payments on a fixed
 * delay. Axon Framework 5 has no {@code DeadlineManager}, so a scheduled projection takes its place.
 * <p>
 * JPA backs the two components that keep state of their own, the repository recipe and the pending-payment to-do
 * list, so both can commit in the same transaction as the tracking token. That property is the whole point of the
 * repository recipe, and an in-memory map could not demonstrate it.
 * <p>
 * Note that no {@code @EntityScan} is declared. Spring Boot already registers entities under this package, and
 * Axon's {@code JpaAutoConfiguration} adds its own through {@code @RegisterDefaultEntities}. Declaring
 * {@code @EntityScan} here would replace both sets rather than add to them.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@SpringBootApplication
@EnableScheduling
public class SagaRecipesApplication {

    /**
     * Starts the application.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        new SpringApplication(SagaRecipesApplication.class).run(args);
    }
}
