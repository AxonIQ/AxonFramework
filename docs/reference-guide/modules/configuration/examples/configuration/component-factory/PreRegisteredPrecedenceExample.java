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

// tag::pre-registered-precedence-example[]
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;

class PreRegisteredPrecedenceExample {

    void register() {
        EventSourcingConfigurer.create()
                               .componentRegistry(cr -> {
                                   // explicit registration wins for "storageEngine@billing"
                                   cr.registerComponent(
                                           EventStorageEngine.class, "storageEngine@billing",
                                           config -> new InMemoryEventStorageEngine()
                                   );
                                   cr.registerFactory(new ContextEventStorageEngineFactory());
                               });
    }
}
// end::pre-registered-precedence-example[]
