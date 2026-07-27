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
 * The demo harness shared by both multi-tenancy demos, grouped by what each part does:
 * <ul>
 *     <li>{@link org.axonframework.examples.demo.multitenancy.shared.run run} runs the scenario and
 *     reports what it observed;</li>
 *     <li>{@link org.axonframework.examples.demo.multitenancy.shared.messaging messaging} drives the
 *     command and query gateways;</li>
 *     <li>{@link org.axonframework.examples.demo.multitenancy.shared.tenant tenant} supplies the tenants
 *     and their per-tenant components;</li>
 *     <li>{@link org.axonframework.examples.demo.multitenancy.shared.audit audit} is the tenant-scoped
 *     audit component.</li>
 * </ul>
 * Both the declarative and the Spring Boot demo drive the same lifecycle through these.
 */
@NullMarked
package org.axonframework.examples.demo.multitenancy.shared;

import org.jspecify.annotations.NullMarked;
