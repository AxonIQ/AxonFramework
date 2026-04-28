/*
 * Copyright (c) 2010-2018. Axon Framework
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

import org.axonframework.modelling.command.RepositoryProvider;
import org.axonframework.modelling.command.inspection.AggregateModel;
import org.axonframework.eventsourcing.eventstore.EventStore;

import java.io.Serializable;

public class AggregateCacheEntry<T> implements Serializable {

    private final T aggregateRoot;
    private final Long version;
    private final boolean deleted;
    private final SnapshotTrigger snapshotTrigger;

    private final transient EventSourcedAggregate<T> aggregate;

    public AggregateCacheEntry(EventSourcedAggregate<T> aggregate) {
        this(aggregate, aggregate.version());
    }

    /**
     * Creates a cache entry with an explicit version. Intended for event stores that assign sequence numbers at
     * transaction commit time rather than at event-apply time (e.g. DCB-mode AxonServer), where the
     * locally-predicted sequence number on the aggregate may differ from the one actually stored.
     */
    public AggregateCacheEntry(EventSourcedAggregate<T> aggregate, Long version) {
        this.aggregate = aggregate;
        this.aggregateRoot = aggregate.getAggregateRoot();
        this.version = version;
        this.deleted = aggregate.isDeleted();
        this.snapshotTrigger =
                (aggregate.getSnapshotTrigger() instanceof Serializable) ? aggregate.getSnapshotTrigger() :
                        NoSnapshotTriggerDefinition.TRIGGER;
    }

    /**
     * Returns the version (sequence number) stored in this cache entry. Used by
     * {@link CachingEventSourcingRepository} to detect whether the entry has been superseded by a more recent commit.
     */
    Long getVersion() {
        return version;
    }

    public EventSourcedAggregate<T> recreateAggregate(AggregateModel<T> model,
                                                      EventStore eventStore,
                                                      SnapshotTriggerDefinition snapshotTriggerDefinition) {
        return recreateAggregate(model, eventStore, null, snapshotTriggerDefinition);
    }

    public EventSourcedAggregate<T> recreateAggregate(AggregateModel<T> model, EventStore eventStore,
                                                      RepositoryProvider repositoryProvider,
                                                      SnapshotTriggerDefinition snapshotTriggerDefinition) {
        if (aggregate != null) {
            return aggregate;
        }
        return EventSourcedAggregate.reconstruct(aggregateRoot, model, version, deleted, eventStore, repositoryProvider,
                                                 snapshotTriggerDefinition
                                                         .reconfigure(aggregateRoot.getClass(), this.snapshotTrigger)
        );
    }
}
