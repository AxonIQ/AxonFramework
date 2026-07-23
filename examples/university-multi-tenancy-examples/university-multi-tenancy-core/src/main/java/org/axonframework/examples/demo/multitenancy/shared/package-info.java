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

/**
 * Driving utilities shared by both multi-tenancy demos: enrolling students and reading their
 * statistics back through the message gateways ({@link
 * org.axonframework.examples.demo.multitenancy.shared.Enrollments}), rendering a tenant's isolated view
 * ({@link org.axonframework.examples.demo.multitenancy.shared.TenantView}), provisioning Axon Server
 * contexts ({@link org.axonframework.examples.demo.multitenancy.shared.AxonServerTenantContextManager}), and
 * the observed-outcome record ({@link org.axonframework.examples.demo.multitenancy.shared.DemoOutcome}).
 * Both the declarative and the Spring Boot demo drive the same lifecycle through these.
 */
@NullMarked
package org.axonframework.examples.demo.multitenancy.shared;

import org.jspecify.annotations.NullMarked;
