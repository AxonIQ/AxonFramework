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

package org.axonframework.eventsourcing.eventstore.inmemory;

import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStoreTestSuite;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

/**
 * Test class validating the {@link StorageEngineBackedEventStore} together with the
 * {@link InMemoryEventStorageEngine}.
 *
 * @author John Hendrikx
 */
class InMemoryStorageEngineBackedEventStoreTest
        extends StorageEngineBackedEventStoreTestSuite<InMemoryEventStorageEngine> {

    private static final UnitOfWorkFactory FACTORY = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);

    private static InMemoryEventStorageEngine engine;

    @BeforeAll
    static void beforeAll() {
        engine = new InMemoryEventStorageEngine();
    }

    @NonNull
    @Override
    protected InMemoryEventStorageEngine getStorageEngine(@NonNull EventConverter converter) {
        return engine;
    }

    @NonNull
    @Override
    protected UnitOfWork unitOfWork() {
        return FACTORY.create();
    }
}
