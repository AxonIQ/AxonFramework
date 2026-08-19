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
package configuration.configurer.component;

// tag::named-components-example[]
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;

class NamedComponentsExample {

    void register(ComponentRegistry registry) {
        registry.registerComponent(
                EventStorageEngine.class,
                "primary-context",
                config -> new InMemoryEventStorageEngine()
        );
        registry.registerComponent(
                EventStorageEngine.class,
                "archive-context",
                config -> new InMemoryEventStorageEngine()
        );
    }
}
// end::named-components-example[]
