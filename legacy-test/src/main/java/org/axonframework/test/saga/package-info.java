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
 * Test support for the sagas carried by {@code axon-legacy}.
 * <p>
 * The package name matches Axon Framework 4, so a migrating saga test suite keeps its imports. What it contains is a
 * thin layer over {@link org.axonframework.test.fixture.AxonTestFixture}: the given-when-then flow, the recording of
 * published events and dispatched commands, and the configuration all come from there, while this package adds the
 * saga-specific pieces, such as asserting on the contents of a
 * {@link org.axonframework.modelling.saga.repository.SagaStore}.
 * <p>
 * A test that does not need the Axon Framework 4 API can use {@code AxonTestFixture} directly and reach for the
 * assertion helpers here through its {@code then().expect(..)} operation.
 */
@NullMarked
package org.axonframework.test.saga;

import org.jspecify.annotations.NullMarked;
