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

package org.axonframework.examples.demo.multitenancy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstraps the multi-tenancy demo through Spring Boot autoconfiguration.
 * <p>
 * There is no multi-tenancy wiring here. The Axoniq Framework Spring Boot starter contributes the
 * multi-tenancy autoconfiguration, which picks up the tenant provider and the per-tenant component
 * providers declared as beans in {@link UniversityConfiguration} and installs the tenant parameter
 * resolver and interceptor for command and query handlers. The tenant lifecycle the demo runs lives in
 * the shared module, so this demo proves the exact same behavior as the declarative demo, differing
 * only in that Spring Boot does the wiring.
 */
@SpringBootApplication
public class MultiTenancyApplication {

    /**
     * Entry point launching the Spring Boot application, whose runner drives the demo end to end.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        SpringApplication.run(MultiTenancyApplication.class, args);
    }
}
