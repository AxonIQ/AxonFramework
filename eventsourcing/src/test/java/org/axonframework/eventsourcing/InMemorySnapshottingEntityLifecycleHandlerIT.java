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

package org.axonframework.eventsourcing;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;

/**
 * Tests the {@link SnapshottingEntityLifecycleHandler} with an {@link InMemorySnapshotStore}.
 *
 * @author John Hendrikx
 */
public class InMemorySnapshottingEntityLifecycleHandlerIT extends SnapshottingEntityLifecycleHandlerTestSuite {
    @Override
    protected void registerComponents(ComponentRegistry registry) {
        registry.registerComponent(SnapshotStore.class, c -> new InMemorySnapshotStore());
    }
}
