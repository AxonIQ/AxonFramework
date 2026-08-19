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

package org.axonframework.eventsourcing.configuration.packagelevel;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.Snapshotting;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

/**
 * Test entity in a package whose {@code package-info.java} carries {@code @Snapshotting(afterEvents = 50)}.
 * The bare {@code @Snapshotting} here has no concrete values, so resolution falls through to the package annotation.
 *
 * @author John Hendrikx
 */
@EventSourcedEntity
@Snapshotting
public record PackageLevelCourse(CourseId id) {

    @EntityCreator
    public PackageLevelCourse {
    }

    public record CourseId() {

    }
}
