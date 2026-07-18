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
package tuning.snapshotting.policies;

import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;

import java.time.Duration;

/**
 * Illustrates composing a {@link SnapshotPolicy} out of the standard building blocks on the snapshotting page.
 */
class SnapshotPolicyExamples {

    static SnapshotPolicy combinedPolicy() {
        // tag::combined-snapshot-policy[]
        SnapshotPolicy snapshotPolicy =
                SnapshotPolicy.afterEvents(5)
                              .or(SnapshotPolicy.whenSourcingTimeExceeds(
                                  Duration.ofMillis(500)
                              ));
        // end::combined-snapshot-policy[]
        return snapshotPolicy;
    }
}
