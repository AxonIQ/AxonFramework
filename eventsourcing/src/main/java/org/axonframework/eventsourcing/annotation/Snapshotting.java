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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures snapshotting behaviour for an event-sourced entity when using the annotation-based registration path
 * (see {@link EventSourcedEntityModule#autodetected(Class, Class)}).
 * <p>
 * Place this annotation on an {@link EventSourcedEntity @EventSourcedEntity}-annotated class to declare one or both
 * snapshot trigger conditions:
 * <ul>
 *     <li>{@link #afterEvents()} — trigger a snapshot after a fixed number of events have been applied during
 *     sourcing.</li>
 *     <li>{@link #afterSourcingTime()} — trigger a snapshot when the total sourcing time for a load operation
 *     exceeds the given duration.</li>
 * </ul>
 * When both attributes are active, either condition independently triggers a snapshot (logical OR), matching the
 * composition semantics of {@link SnapshotPolicy#or(SnapshotPolicy)}.
 * <p>
 * Both attributes default to sentinel values ({@link #USE_DEFAULT} and {@link #USE_DEFAULT_DURATION}) meaning
 * "not configured here". At least one attribute must resolve to a concrete value, either directly on the annotation
 * or via a higher-level annotation on an enclosing class, package, or module. If no concrete value can be resolved,
 * configuration fails with an {@link AxonConfigurationException}.
 * <p>
 * This makes it possible to define a shared snapshotting policy at the package or module level and let individual
 * entities inherit it by simply placing {@code @Snapshotting} without further attributes:
 * <pre>{@code
 * // package-info.java
 * @Snapshotting(afterEvents = 50)
 * package com.example.orders;
 * }</pre>
 * <p>
 * Example usage:
 * {@snippet :
 * @EventSourcedEntity
 * @Snapshotting(afterEvents = 100)  // snapshot after every 100 events
 * public class BankAccount { ... }
 *
 * @EventSourcedEntity
 * @Snapshotting(afterEvents = 100, afterSourcingTime = "PT5S")  // combined thresholds
 * public class OrderAggregate { ... }
 * }
 *
 * @author John Hendrikx
 * @see SnapshotPolicy
 * @see org.axonframework.eventsourcing.configuration.EventSourcedEntityModule.OptionalPhase#snapshotPolicy(org.axonframework.common.configuration.ComponentBuilder)
 * @since 5.1.1
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Snapshotting {

    /**
     * Sentinel value for {@link #afterEvents()} indicating that no event-count threshold is configured at this level.
     * The framework will look for a concrete value in enclosing classes, the package, or the module.
     */
    int USE_DEFAULT = Integer.MIN_VALUE;

    /**
     * Sentinel value for {@link #afterSourcingTime()} indicating that no sourcing-time threshold is configured at
     * this level. The framework will look for a concrete value in enclosing classes, the package, or the module.
     */
    String USE_DEFAULT_DURATION = "\0";

    /**
     * The number of events after which a snapshot is triggered during sourcing. Set to a positive integer to enable.
     * Set to {@code 0} to explicitly disable this trigger. Defaults to {@link #USE_DEFAULT}, meaning the value
     * is resolved from a higher-level annotation.
     *
     * @return the event count threshold, {@code 0} to disable, or {@link #USE_DEFAULT} to inherit
     */
    int afterEvents() default USE_DEFAULT;

    /**
     * The maximum sourcing duration after which a snapshot is triggered, expressed as an ISO-8601 duration string
     * (e.g., {@code "PT5S"} for five seconds). Defaults to {@link #USE_DEFAULT_DURATION}, meaning the value
     * is resolved from a higher-level annotation.
     *
     * @return the sourcing-time threshold as an ISO-8601 duration, or {@link #USE_DEFAULT_DURATION} to inherit
     */
    String afterSourcingTime() default USE_DEFAULT_DURATION;
}
