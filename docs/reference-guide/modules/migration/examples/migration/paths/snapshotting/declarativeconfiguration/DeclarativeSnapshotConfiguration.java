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
package migration.paths.snapshotting.declarativeconfiguration;

import org.axonframework.conversion.Converter;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

/**
 * Shows how snapshotting is enabled declaratively by adding a {@link SnapshotPolicy} to the
 * {@link EventSourcedEntityModule} and registering a {@link SnapshotStore}, as shown on the snapshotting
 * migration page.
 */
class DeclarativeSnapshotConfiguration {

    static void configure() {
        // tag::snapshot-declarative-configuration[]
        SnapshotPolicy snapshotPolicy = SnapshotPolicy.afterEvents(250);

        EventSourcedEntityModule<String, Account> accountModule =
                EventSourcedEntityModule.declarative(String.class, Account.class)
                // end::snapshot-declarative-configuration[]
                                        .messagingModel((config, model) -> model.build())
                                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(Account::new))
                                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(
                                                Tag.of("accountId", id)
                                        ))
                // tag::snapshot-declarative-configuration[]
                                        .snapshotPolicy(snapshotPolicy)
                                        .build();

        EventSourcingConfigurer.create()
                .componentRegistry(cr -> cr.registerComponent(
                        SnapshotStore.class,
                        c -> new InMemorySnapshotStore(c.getComponent(Converter.class))
                ))
                .componentRegistry(cr -> cr.registerModule(accountModule))
                .start();
        // end::snapshot-declarative-configuration[]
    }
}

/**
 * Placeholder entity used by this sample; its command handling and event sourcing behavior is not relevant here.
 */
class Account {
}
