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

package org.axonframework.messaging.eventsourcing.snapshotting;

import org.axonframework.common.ClockUtils;

import java.time.Clock;

/**
 * A Snapshotter trigger mechanism which based on the loading time of an Aggregate decides when to trigger the creation
 * of a snapshot. A snapshot is triggered when loading the aggregate exceeds the given {@code loadTimeMillisThreshold}
 * in milliseconds.
 * <p>
 * This threshold can be exceeded in two distinct scenarios:
 * <ol>
 *     <li> When initializing / event sourcing the aggregate in question.</li>
 *     <li> When new events are being applied by the aggregate.</li>
 * </ol>
 * <p>
 * If the definable {@code loadTimeMillisThreshold} is met in situation one, the snapshot will be triggered regardless of the outcome
 * of command handling. Thus also if command handling returns exceptionally. If the {@code loadTimeMillisThreshold} is only reached
 * once the aggregate has been fully initialized, than the snapshot will only be triggered if handling resolves
 * successfully.
 *
 * @author Yvonne Ceelie
 * @since 4.4.4
 */
public class AggregateLoadTimeSnapshotTriggerDefinition implements SnapshotTriggerDefinition {

    private final Snapshotter snapshotter;
    private final long loadTimeMillisThreshold;

    /**
     * Initialize a {@link SnapshotTriggerDefinition} to trigger snapshot creation using the given {@code snapshotter}
     * when loading the aggregate instance takes longer than the given {@code loadTimeMillisThreshold}.
     *
     * @param snapshotter             the snapshotter to notify when a snapshot needs to be taken
     * @param loadTimeMillisThreshold the maximum time that loading an aggregate may take
     */
    public AggregateLoadTimeSnapshotTriggerDefinition(Snapshotter snapshotter, long loadTimeMillisThreshold) {
        this.snapshotter = snapshotter;
        this.loadTimeMillisThreshold = loadTimeMillisThreshold;
    }

    @Override
    public SnapshotTrigger prepareTrigger(Class<?> aggregateType) {
        return new AggregateLoadTimeSnapshotTrigger(snapshotter, aggregateType, loadTimeMillisThreshold);
    }

    @Override
    public SnapshotTrigger reconfigure(Class<?> aggregateType, SnapshotTrigger trigger) {
        if (trigger instanceof AggregateLoadTimeSnapshotTrigger) {
            ((AggregateLoadTimeSnapshotTrigger) trigger).setSnapshotter(snapshotter);
            return trigger;
        }
        return new AggregateLoadTimeSnapshotTrigger(snapshotter, aggregateType, loadTimeMillisThreshold);
    }

    private static class AggregateLoadTimeSnapshotTrigger extends AbstractSnapshotTrigger {

        private final long loadTimeMillisThreshold;
        private long startTime = ClockUtils.instant().toEpochMilli();

        public AggregateLoadTimeSnapshotTrigger(Snapshotter snapshotter,
                                                Class<?> aggregateType,
                                                long loadTimeMillisThreshold) {
            super(snapshotter, aggregateType);
            this.loadTimeMillisThreshold = loadTimeMillisThreshold;
        }

        @Override
        public boolean exceedsThreshold() {
            return (ClockUtils.instant().toEpochMilli() - startTime) > loadTimeMillisThreshold;
        }

        @Override
        public void reset() {
            startTime = ClockUtils.instant().toEpochMilli();
        }
    }
}
