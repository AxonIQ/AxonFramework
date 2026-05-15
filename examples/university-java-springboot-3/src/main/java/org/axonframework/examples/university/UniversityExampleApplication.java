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

package org.axonframework.examples.university;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * Main application class.
 */
@SpringBootApplication
@EntityScan(basePackages = {
        "org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa",
        "org.axonframework.eventsourcing.eventstore.jpa"
})
public class UniversityExampleApplication {

    /**
     * Starts the application
     *
     * @param args command line args.
     */
    public static void main(String[] args) {
        new SpringApplication(UniversityExampleApplication.class).run(args);
    }
}
