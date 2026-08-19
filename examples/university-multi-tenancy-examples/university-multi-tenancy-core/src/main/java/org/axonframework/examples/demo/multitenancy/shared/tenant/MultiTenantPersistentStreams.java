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

package org.axonframework.examples.demo.multitenancy.shared.tenant;

import io.axoniq.axonserver.connector.event.PersistentStreamProperties;
import io.axoniq.framework.axonserver.connector.event.PersistentStreamScheduledExecutorBuilder;
import io.axoniq.framework.axonserver.connector.event.PersistentStreamSequencingPolicy;
import io.axoniq.framework.messaging.multitenancy.axonserver.eventstreaming.MultiTenantPersistentStreamEventSourceFactory;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.SubscribableEventSource;

import java.util.Collections;
import java.util.Objects;

/**
 * Builds the {@link SubscribableEventSource} the declarative demo hands to a subscribing processor to run it on
 * a persistent stream, consumed from every tenant's own Axon Server context and fanned into that one processor.
 * <p>
 * The Spring Boot demo needs none of this: its starter fabricates the same kind of source from properties alone
 * ({@code axon.axonserver.auto-persistent-streams-enabled} together with multi-tenancy on the classpath). The
 * declarative demo has no such auto-configuration layer, so it builds the source itself, through the same
 * {@link MultiTenantPersistentStreamEventSourceFactory} the Spring Boot starter uses underneath.
 *
 * @author Jakob Hatzl
 * @since 5.3.0
 */
public final class MultiTenantPersistentStreams {

    private static final int SEGMENT_COUNT = 1;
    private static final int BATCH_SIZE = 10;

    private MultiTenantPersistentStreams() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Builds the multi-tenant persistent stream source named {@code streamName}, one ordinary persistent stream
     * per tenant under that same name in every tenant's context, fanned into a single consumer.
     * <p>
     * Each tenant's stream runs on a scheduler of its own, named after the stream and the tenant it serves, so a
     * thread dump shows whose stream a thread is working on and a retrying tenant cannot starve the others.
     *
     * @param streamName    the name the stream carries in every tenant's Axon Server context
     * @param configuration the started configuration to resolve the tenant provider and connector components from
     * @return the multi-tenant {@link SubscribableEventSource} for the named stream
     */
    public static SubscribableEventSource eventSource(String streamName, Configuration configuration) {
        Objects.requireNonNull(streamName, "The stream name must not be null");
        Objects.requireNonNull(configuration, "The configuration must not be null");
        PersistentStreamProperties properties = new PersistentStreamProperties(
                streamName,
                SEGMENT_COUNT,
                PersistentStreamSequencingPolicy.SEQUENTIAL_POLICY,
                Collections.emptyList(),
                PersistentStreamProperties.TAIL_POSITION,
                null);
        return new MultiTenantPersistentStreamEventSourceFactory().build(
                streamName,
                properties,
                poolName -> PersistentStreamScheduledExecutorBuilder.defaultFactory().build(SEGMENT_COUNT, poolName),
                BATCH_SIZE,
                configuration);
    }
}
