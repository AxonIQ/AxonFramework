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

package org.axonframework.extension.springboot.compatibility;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application relying entirely on the Axon Spring Boot starter's default auto-configuration
 * (JPA event store, default JPA bootstrap mode) to start.
 * <p>
 * Shared, unmodified, across every Spring Boot version module in this matrix - only annotations and APIs that are
 * stable across those versions are used here.
 *
 * @author Jakob Hatzl
 * @since 5.4.0
 */
@SpringBootApplication
public class CompatibilityTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompatibilityTestApplication.class, args);
    }
}
