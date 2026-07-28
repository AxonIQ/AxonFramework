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
 * Module-level tracing wiring for {@code axon-eventsourcing}. The enhancers and settings live in the
 * {@code configuration} sub-package; component-owned tracing decorators live in
 * {@code org.axonframework.eventsourcing.eventstore.tracing},
 * {@code org.axonframework.eventsourcing.snapshot.store.tracing}, and
 * {@code org.axonframework.eventsourcing.handler.tracing}.
 */
@NullMarked
package org.axonframework.eventsourcing.tracing;

import org.jspecify.annotations.NullMarked;
