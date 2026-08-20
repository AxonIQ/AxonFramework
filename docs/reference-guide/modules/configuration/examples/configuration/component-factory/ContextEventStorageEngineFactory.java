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
package configuration.componentfactory;

// tag::context-event-storage-engine-factory-example[]
import org.axonframework.common.configuration.Component;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentFactory;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;

import java.util.Optional;

public class ContextEventStorageEngineFactory implements ComponentFactory<EventStorageEngine> {

    @Override
    public Class<EventStorageEngine> forType() {
        return EventStorageEngine.class;
    }

    @Override
    public Optional<Component<EventStorageEngine>> construct(String name, Configuration config) {
        // only handle names with the format "storageEngine@{context-name}"
        if (!name.startsWith("storageEngine@")) {
            return Optional.empty();
        }
        EventStorageEngine engine = new InMemoryEventStorageEngine();
        // ComponentDefinition is a sealed interface permitting only ComponentCreator implementations
        ComponentDefinition.ComponentCreator<EventStorageEngine> definition =
                (ComponentDefinition.ComponentCreator<EventStorageEngine>)
                        ComponentDefinition.ofTypeAndName(EventStorageEngine.class, name).withInstance(engine);
        return Optional.of(definition.createComponent());
    }

    @Override
    public void registerShutdownHandlers(LifecycleRegistry registry) {
        // no explicit shutdown behavior is required for an in-memory storage engine
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("prefix", "storageEngine@");
    }
}
// end::context-event-storage-engine-factory-example[]
