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
 * The course write side, where both multi-tenancy features meet in one command handler. A {@link
 * org.axonframework.examples.demo.multitenancy.university.write.course.Course} is opened with a fixed
 * number of seats and students enroll into it. Handling {@link
 * org.axonframework.examples.demo.multitenancy.university.write.course.EnrollStudent} sources the
 * event-sourced course from, and appends to, the tenant's own event store (per-tenant event storage), and
 * updates the tenant's {@code @TenantScoped} read-model components (per-tenant component injection), each
 * resolved from the tenant of the message.
 */
@NullMarked
package org.axonframework.examples.demo.multitenancy.university.write.course;

import org.jspecify.annotations.NullMarked;
