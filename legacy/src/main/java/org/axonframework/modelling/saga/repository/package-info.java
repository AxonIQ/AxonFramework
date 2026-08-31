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
 * The {@link org.axonframework.modelling.saga.repository.SagaStore} abstraction and the decorators over it.
 * <p>
 * A {@code SagaStore} finds, loads, stores and deletes saga instances together with their association values. Backing
 * implementations live in the {@code inmemory}, {@code jpa} and {@code jdbc} sub-packages;
 * {@link org.axonframework.modelling.saga.repository.CachingSagaStore} decorates any of them with a cache for saga
 * instances and for association lookups.
 * <p>
 * These types carry the Axon Framework 4 API unchanged, to ease migration of projects that cannot move off it in one
 * go.
 */
@NullMarked
package org.axonframework.modelling.saga.repository;

import org.jspecify.annotations.NullMarked;
