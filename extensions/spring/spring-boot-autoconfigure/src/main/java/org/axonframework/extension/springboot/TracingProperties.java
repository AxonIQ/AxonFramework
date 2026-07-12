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

package org.axonframework.extension.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring Boot configuration properties for Axon Framework distributed tracing.
 * <p>
 * Binds to the {@code axon.tracing.*} property namespace. The {@link #isEnabled() master switch} toggles tracing
 * autoconfiguration as a whole, while nested settings allow tuning individual concerns such as which messaging
 * components are decorated:
 * <pre>{@code
 * axon:
 *   tracing:
 *     enabled: true
 *     command-bus:
 *       enabled: true
 * }</pre>
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
@ConfigurationProperties(prefix = "axon.tracing")
public class TracingProperties {

    /**
     * Master switch enabling Axon Framework tracing autoconfiguration. Defaults to {@code true}.
     */
    private boolean enabled = true;

    /**
     * Whether {@code @EventSourcingHandler} invocations get their own per-method handler span. Defaults to
     * {@code false}: event sourcing handlers fire once per event during entity replay and would flood traces with one
     * span per replayed event.
     */
    private boolean showEventSourcingHandlers = false;

    /**
     * Tracing settings for the {@code CommandBus}.
     */
    private final CommandBus commandBus = new CommandBus();

    /**
     * Tracing settings for the {@code EventSink} (event publication).
     */
    private final EventSink eventSink = new EventSink();

    /**
     * Tracing settings for event processors (event handling).
     */
    private final EventProcessor eventProcessor = new EventProcessor();

    /**
     * Tracing settings for the {@code QueryBus} (queries and subscription-query updates).
     */
    private final QueryBus queryBus = new QueryBus();

    /**
     * Tracing settings for {@code Repository}.
     */
    private final Repository repository = new Repository();

    /**
     * Tracing settings for {@code StateManager}.
     */
    private final StateManager stateManager = new StateManager();

    /**
     * Tracing settings for the {@code EventStorageEngine}.
     */
    private final EventStore eventStore = new EventStore();

    /**
     * Tracing settings for {@code SnapshotStore}.
     */
    private final SnapshotStore snapshotStore = new SnapshotStore();

    /**
     * Toggles for the built-in {@code SpanAttributesProvider}s.
     */
    private final AttributeProviders attributeProviders = new AttributeProviders();

    /**
     * Returns whether tracing autoconfiguration is enabled.
     *
     * @return {@code true} when tracing is enabled, {@code false} otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether tracing autoconfiguration is enabled.
     *
     * @param enabled {@code true} to enable tracing, {@code false} to disable it
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns whether {@code @EventSourcingHandler} invocations get their own per-method handler span.
     *
     * @return {@code true} when event sourcing handlers are traced, {@code false} otherwise
     */
    public boolean isShowEventSourcingHandlers() {
        return showEventSourcingHandlers;
    }

    /**
     * Sets whether {@code @EventSourcingHandler} invocations get their own per-method handler span.
     *
     * @param showEventSourcingHandlers {@code true} to trace event sourcing handlers, {@code false} to suppress them
     */
    public void setShowEventSourcingHandlers(boolean showEventSourcingHandlers) {
        this.showEventSourcingHandlers = showEventSourcingHandlers;
    }

    /**
     * Returns the tracing settings for the {@code CommandBus}.
     *
     * @return the {@code CommandBus} tracing settings, never {@code null}
     */
    public CommandBus getCommandBus() {
        return commandBus;
    }

    /**
     * Returns the tracing settings for the {@code EventSink}.
     *
     * @return the {@code EventSink} tracing settings, never {@code null}
     */
    public EventSink getEventSink() {
        return eventSink;
    }

    /**
     * Returns the tracing settings for event processors.
     *
     * @return the event-processor tracing settings, never {@code null}
     */
    public EventProcessor getEventProcessor() {
        return eventProcessor;
    }

    /**
     * Returns the tracing settings for the {@code QueryBus}.
     *
     * @return the {@code QueryBus} tracing settings, never {@code null}
     */
    public QueryBus getQueryBus() {
        return queryBus;
    }

    /**
     * Returns the tracing settings for {@code Repository}.
     *
     * @return the {@code Repository} tracing settings, never {@code null}
     */
    public Repository getRepository() {
        return repository;
    }

    /**
     * Returns the tracing settings for {@code StateManager}.
     *
     * @return the {@code StateManager} tracing settings, never {@code null}
     */
    public StateManager getStateManager() {
        return stateManager;
    }

    /**
     * Returns the tracing settings for the {@code EventStorageEngine}.
     *
     * @return the event-store tracing settings, never {@code null}
     */
    public EventStore getEventStore() {
        return eventStore;
    }

    /**
     * Returns the tracing settings for {@code SnapshotStore}.
     *
     * @return the {@code SnapshotStore} tracing settings, never {@code null}
     */
    public SnapshotStore getSnapshotStore() {
        return snapshotStore;
    }

    /**
     * Returns the toggles for the built-in {@code SpanAttributesProvider}s.
     *
     * @return the attribute-provider toggles, never {@code null}
     */
    public AttributeProviders getAttributeProviders() {
        return attributeProviders;
    }

    /**
     * Tracing settings for the {@code CommandBus}.
     */
    public static class CommandBus {

        /**
         * Whether the {@code CommandBus} is decorated with tracing. Defaults to {@code true}.
         */
        private boolean enabled = true;

        /**
         * Returns whether the {@code CommandBus} is decorated with tracing.
         *
         * @return {@code true} when {@code CommandBus} tracing is enabled, {@code false} otherwise
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether the {@code CommandBus} is decorated with tracing.
         *
         * @param enabled {@code true} to enable {@code CommandBus} tracing, {@code false} to disable it
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Tracing settings for the {@code EventSink} (event publication).
     */
    public static class EventSink {

        /**
         * Whether the {@code EventSink} is decorated with tracing. Defaults to {@code true}.
         */
        private boolean enabled = true;

        /**
         * Returns whether the {@code EventSink} is decorated with tracing.
         *
         * @return {@code true} when {@code EventSink} tracing is enabled, {@code false} otherwise
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether the {@code EventSink} is decorated with tracing.
         *
         * @param enabled {@code true} to enable {@code EventSink} tracing, {@code false} to disable it
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Tracing settings for event processors (event handling).
     */
    public static class EventProcessor {

        /**
         * Whether event-handling components are decorated with tracing. Defaults to {@code true}.
         */
        private boolean enabled = true;

        /**
         * When {@code true}, the streaming-processor batch span is suppressed. Each event handler invocation still
         * gets its own span; only the enclosing batch root is dropped. Defaults to {@code false}.
         */
        private boolean disableBatchTrace = false;

        /**
         * When {@code true}, the handler span continues the publisher's trace. When {@code false} (default), the
         * handler span links back to the publisher and is parented to the streaming batch span. If batch tracing is
         * disabled, it starts a new trace with the same publisher link.
         */
        private boolean distributedInSameTrace = false;

        /**
         * How recent an event must be to continue the publisher's trace when {@code distributedInSameTrace} is
         * {@code true}. Older events -- typically replays -- start their own trace linked back to the publisher
         * instead of stretching the publisher's long-finished trace. Defaults to two minutes.
         */
        private Duration distributedInSameTraceTimeLimit = Duration.ofMinutes(2);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDisableBatchTrace() {
            return disableBatchTrace;
        }

        public void setDisableBatchTrace(boolean disableBatchTrace) {
            this.disableBatchTrace = disableBatchTrace;
        }

        public boolean isDistributedInSameTrace() {
            return distributedInSameTrace;
        }

        public void setDistributedInSameTrace(boolean distributedInSameTrace) {
            this.distributedInSameTrace = distributedInSameTrace;
        }

        public Duration getDistributedInSameTraceTimeLimit() {
            return distributedInSameTraceTimeLimit;
        }

        public void setDistributedInSameTraceTimeLimit(Duration distributedInSameTraceTimeLimit) {
            this.distributedInSameTraceTimeLimit = distributedInSameTraceTimeLimit;
        }
    }

    /**
     * Tracing settings for the {@code QueryBus} (queries and subscription-query updates).
     */
    public static class QueryBus {

        /**
         * Whether the {@code QueryBus} is decorated with tracing. Defaults to {@code true}.
         */
        private boolean enabled = true;

        /**
         * Returns whether the {@code QueryBus} is decorated with tracing.
         *
         * @return {@code true} when {@code QueryBus} tracing is enabled, {@code false} otherwise
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether the {@code QueryBus} is decorated with tracing.
         *
         * @param enabled {@code true} to enable {@code QueryBus} tracing, {@code false} to disable it
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Tracing settings for {@code Repository}.
     */
    public static class Repository {

        /**
         * Whether {@code Repository} instances are decorated with tracing. Defaults to {@code true}.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Tracing settings for {@code StateManager}.
     */
    public static class StateManager {

        /**
         * Whether the {@code StateManager} is decorated with tracing. Defaults to {@code true}.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Tracing settings for the {@code EventStorageEngine}.
     */
    public static class EventStore {

        /**
         * Whether the {@code EventStorageEngine} is decorated with tracing. Defaults to {@code true}.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Tracing settings for {@code SnapshotStore}.
     */
    public static class SnapshotStore {

        /**
         * Whether the {@code SnapshotStore} is decorated with tracing. Defaults to {@code true}.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Toggles for the built-in {@code SpanAttributesProvider}s, bound at
     * {@code axon.tracing.attribute-providers.*}. Each toggle decides whether the corresponding provider is
     * contributed to the {@code SpanAttributesProviderRegistry} the {@code SpanFactory} is constructed with.
     */
    public static class AttributeProviders {

        /**
         * Whether the message-id provider ({@code axoniq.message.id}) is contributed. Defaults to {@code true}.
         */
        private boolean messageId = true;

        /**
         * Whether the message-type provider ({@code axoniq.message.type}) is contributed. Defaults to {@code true}.
         */
        private boolean messageType = true;

        /**
         * Whether the metadata provider ({@code axoniq.metadata.*}) is contributed. Defaults to {@code true}.
         */
        private boolean metadata = true;

        /**
         * Whether the aggregate-identifier provider ({@code axoniq.aggregate.identifier}) is contributed. Defaults to
         * {@code true}.
         */
        private boolean aggregateIdentifier = true;

        /**
         * Whether the event-tags provider ({@code axoniq.event_tag.*}) is contributed. Defaults to {@code true}.
         */
        private boolean eventTags = true;

        public boolean isMessageId() {
            return messageId;
        }

        public void setMessageId(boolean messageId) {
            this.messageId = messageId;
        }

        public boolean isMessageType() {
            return messageType;
        }

        public void setMessageType(boolean messageType) {
            this.messageType = messageType;
        }

        public boolean isMetadata() {
            return metadata;
        }

        public void setMetadata(boolean metadata) {
            this.metadata = metadata;
        }

        public boolean isAggregateIdentifier() {
            return aggregateIdentifier;
        }

        public void setAggregateIdentifier(boolean aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        public boolean isEventTags() {
            return eventTags;
        }

        public void setEventTags(boolean eventTags) {
            this.eventTags = eventTags;
        }
    }
}
