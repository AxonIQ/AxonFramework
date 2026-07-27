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
 * The single-process layer of the hunt suite: real Axon Framework components, driven under fault.
 * <p>
 * Everything the workload talks to is the real thing. A real {@code SimpleCommandBus} dispatches, a real
 * {@code StorageEngineBackedEventStore} appends and sources, and a real {@code PooledStreamingEventProcessor} builds
 * the projection. The only substitution is
 * {@link org.axonframework.hunt.harness.ControllableEventStorageEngine}, a wrapper that records what the store was
 * actually asked to do and lets a fault interfere with it. The framework is never patched.
 */
@NullMarked
package org.axonframework.hunt.harness;

import org.jspecify.annotations.NullMarked;
