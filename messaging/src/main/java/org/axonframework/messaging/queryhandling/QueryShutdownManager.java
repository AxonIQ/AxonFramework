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
package org.axonframework.messaging.queryhandling;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.common.lifecycle.ShutdownInProgressException;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages the graceful shutdown of locally-initiated query streams, ensuring they are terminated
 * during application shutdown.
 * <p>
 * When an application shuts down, locally-initiated subscription queries and streaming queries
 * (those started on this node via {@link QueryBus#subscriptionQuery},
 * {@link org.axonframework.messaging.queryhandling.gateway.QueryGateway#subscriptionQuery}, or
 * {@link org.axonframework.messaging.queryhandling.gateway.QueryGateway#streamingQuery}) remain
 * open indefinitely unless explicitly closed. This prevents a web server from completing a graceful
 * shutdown, as active connections (e.g., SSE streams) are kept alive by the open query streams.
 * <p>
 * This manager solves the problem through explicit, call-level opt-in: callers wrap their
 * query streams or publishers using {@link #track(MessageStream)} or {@link #track(Publisher)},
 * and the manager closes them when {@link #shutdown()} is called.
 * <p>
 * Two shutdown policies are available:
 * <ul>
 *     <li><b>Close immediately</b>: all active subscriptions are terminated as soon as
 *     {@link #shutdown()} is called.</li>
 *     <li><b>Wait with grace period</b>: the manager waits up to a configurable duration for
 *     active subscriptions to complete naturally before force-closing any that remain. This is
 *     useful when some queries are short-lived and expected to complete on their own shortly
 *     after shutdown begins.</li>
 * </ul>
 * <p>
 * In a <b>Spring Boot</b> application, the simplest integration is to declare a {@code @Bean} of
 * type {@code QueryShutdownManager}. Axon's auto-configuration detects it and calls
 * {@link #shutdown()} at the correct lifecycle phase. To also apply gateway-level tracking,
 * configure a named {@link org.axonframework.messaging.queryhandling.gateway.QueryGateway}
 * using
 * {@link org.axonframework.messaging.core.configuration.MessagingConfigurer#queryGateway(String, java.util.function.Consumer)}.
 * <p>
 * In a <b>plain Java</b> (non-Spring) application, use
 * {@link org.axonframework.messaging.core.configuration.MessagingConfigurer#queryGateway(String, java.util.function.Consumer)}
 * for gateway-level tracking, where the manager is wired into Axon's lifecycle automatically:
 * <pre>{@code
 * MessagingConfigurer.create()
 *     .queryGateway("sse", g -> g
 *         .cancellingSubscriptionQueryOnShutdown(c -> c.withGracePeriod(Duration.ofSeconds(10))));
 * }</pre>
 * For standalone use with call-site tracking, create the manager directly and register it with
 * Axon's lifecycle using {@link #registerShutdown(LifecycleRegistry)}:
 * <pre>{@code
 * QueryShutdownManager manager = QueryShutdownManager.withGracePeriod(Duration.ofSeconds(5));
 * configurer.lifecycleRegistry(manager::registerShutdown);
 * }</pre>
 * <p>
 * When call-site tracking is needed, for example when multiple managers are defined or when
 * using the query bus directly, wrap results explicitly with {@link #track}:
 * <pre>{@code
 * @Autowired QueryShutdownManager shutdownManager;
 *
 * // Via query gateway (returns Publisher<T>):
 * @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
 * public Publisher<MyDto> stream() {
 *     return shutdownManager.track(
 *         queryGateway.subscriptionQuery(new MyQuery(), MyDto.class)
 *     );
 * }
 *
 * // Via query bus directly (returns MessageStream<T>):
 * MessageStream<QueryResponseMessage> stream = shutdownManager.track(
 *     queryBus.subscriptionQuery(query, null, 100)
 * );
 * }</pre>
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
public class QueryShutdownManager {

    private static final String SHUTDOWN_MESSAGE =
            "Subscription query terminated: application is shutting down";

    private final @Nullable Duration gracePeriod;
    private final ConcurrentMap<Object, TrackedItem> activeItems = new ConcurrentHashMap<>();

    private QueryShutdownManager(@Nullable Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    /**
     * Creates a {@link QueryShutdownManager} that closes all active query streams immediately when
     * {@link #shutdown()} is called.
     *
     * @return a new manager configured to close immediately on shutdown
     */
    public static QueryShutdownManager closeImmediately() {
        return new QueryShutdownManager(null);
    }

    /**
     * Creates a {@link QueryShutdownManager} that waits up to the given {@code gracePeriod} for
     * active query streams to complete naturally before force-closing any remaining ones.
     *
     * @param gracePeriod the maximum time to wait for natural completion before force-closing
     * @return a new manager configured to wait before closing on shutdown
     */
    public static QueryShutdownManager withGracePeriod(Duration gracePeriod) {
        return new QueryShutdownManager(gracePeriod);
    }

    /**
     * Registers {@link #shutdown()} as a shutdown handler on the given {@code registry} at
     * {@link Phase#OUTBOUND_QUERY_CONNECTORS}, the phase at which outbound query connectors shut
     * down. At this point, no new queries can be sent, so cancelling existing subscription query
     * streams is the natural complement.
     * <p>
     * Returns {@code this} for fluent chaining, for example:
     * <pre>{@code
     * QueryShutdownManager manager = QueryShutdownManager.withGracePeriod(Duration.ofSeconds(5));
     * configurer.lifecycleRegistry(manager::registerShutdown);
     * }</pre>
     *
     * @param registry the lifecycle registry to register the shutdown handler with
     * @return this manager, for fluent chaining
     */
    public QueryShutdownManager registerShutdown(LifecycleRegistry registry) {
        registry.onShutdown(Phase.OUTBOUND_QUERY_CONNECTORS, this::shutdown);
        return this;
    }

    /**
     * Tracks the given {@code stream}, registering it for termination when {@link #shutdown()} is called.
     * <p>
     * The stream is automatically deregistered when it closes naturally. The caller must use the
     * returned stream instead of the original, as the deregistration hook is attached to the
     * returned wrapper.
     *
     * @param stream the query stream to track
     * @param <T>    the message type
     * @return a wrapped stream that participates in this manager's lifecycle
     */
    public <T extends Message> MessageStream<T> track(MessageStream<T> stream) {
        Object key = new Object();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        MessageStream<T> tracked = stream.onClose(() -> {
            activeItems.remove(key);
            completion.complete(null);
        });
        activeItems.put(key, new TrackedItem(() -> {
            tracked.close();
            stream.close();
        }, completion));
        return tracked;
    }

    /**
     * Tracks the given {@code source} publisher, registering it for termination when
     * {@link #shutdown()} is called.
     * <p>
     * The publisher is automatically deregistered when it terminates naturally. The caller must
     * subscribe to and use the returned {@link Publisher} instead of the original source. When
     * shutdown is triggered, a {@link ShutdownInProgressException} is emitted as an error to
     * any active subscriber.
     *
     * @param source the query publisher to track
     * @param <T>    the element type
     * @return a {@link Publisher} that participates in this manager's lifecycle
     */
    public <T> Publisher<T> track(Publisher<T> source) {
        Object key = new Object();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Sinks.Empty<T> cancelSink = Sinks.empty();
        activeItems.put(key, new TrackedItem(
                () -> cancelSink.tryEmitError(new ShutdownInProgressException(SHUTDOWN_MESSAGE)),
                completion
        ));
        return Flux.from(source)
                   .doFinally(signal -> cancelSink.tryEmitEmpty())
                   .mergeWith(cancelSink.asMono())
                   .doFinally(signal -> {
                       activeItems.remove(key);
                       completion.complete(null);
                   });
    }

    /**
     * Shuts down all tracked query streams according to this manager's configured policy.
     * <p>
     * If no grace period is configured, all active subscriptions are closed immediately and the
     * returned future completes right away. Otherwise, the returned future completes once all
     * active subscriptions have either completed naturally or been force-closed after the grace
     * period expires.
     *
     * @return a {@link CompletableFuture} that completes when all tracked subscriptions are closed
     */
    public CompletableFuture<Void> shutdown() {
        if (activeItems.isEmpty()) {
            return FutureUtils.emptyCompletedFuture();
        }
        if (gracePeriod == null) {
            closeAll();
            return FutureUtils.emptyCompletedFuture();
        }
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                activeItems.values().stream()
                           .map(TrackedItem::completion)
                           .toArray(CompletableFuture[]::new)
        );
        return allDone.orTimeout(gracePeriod.toMillis(), TimeUnit.MILLISECONDS)
                      .exceptionally(e -> null)
                      .thenRun(this::closeAll);
    }

    private void closeAll() {
        List.copyOf(activeItems.values()).forEach(item -> item.closeAction().run());
    }

    private record TrackedItem(Runnable closeAction, CompletableFuture<Void> completion) {

    }

    /**
     * Entry point for creating a {@link QueryShutdownManager} through the
     * {@link org.axonframework.messaging.queryhandling.configuration.QueryGatewayConfigurer} API.
     * <p>
     * A {@code Spec} instance is supplied as the argument to the configuration lambda passed to
     * {@link org.axonframework.messaging.queryhandling.configuration.QueryGatewayConfigurer#cancellingSubscriptionQueryOnShutdown}
     * or
     * {@link org.axonframework.messaging.queryhandling.configuration.QueryGatewayConfigurer#cancellingStreamingQueryOnShutdown}.
     * Its methods each produce a fully configured {@link QueryShutdownManager}:
     * <pre>{@code
     * MessagingConfigurer.create()
     *     .queryGateway("sse", g -> g
     *         .cancellingSubscriptionQueryOnShutdown(c -> c.withGracePeriod(Duration.ofSeconds(10))));
     * }</pre>
     *
     * @since 5.2.0
     */
    public static class Spec {

        /**
         * Creates a {@link QueryShutdownManager} that closes all active query streams immediately
         * when {@link QueryShutdownManager#shutdown()} is called.
         *
         * @return a new manager configured to close immediately on shutdown
         */
        public QueryShutdownManager closeImmediately() {
            return QueryShutdownManager.closeImmediately();
        }

        /**
         * Creates a {@link QueryShutdownManager} that waits up to the given {@code gracePeriod}
         * for active query streams to complete naturally before force-closing any remaining ones.
         *
         * @param gracePeriod the maximum time to wait for natural completion before force-closing
         * @return a new manager configured to wait before closing on shutdown
         */
        public QueryShutdownManager withGracePeriod(Duration gracePeriod) {
            return QueryShutdownManager.withGracePeriod(gracePeriod);
        }

        /**
         * Creates a {@link QueryShutdownManager} that waits up to the given number of seconds for
         * active query streams to complete naturally before force-closing any remaining ones.
         *
         * @param seconds the maximum number of seconds to wait for natural completion before force-closing
         * @return a new manager configured to wait before closing on shutdown
         */
        public QueryShutdownManager withGracePeriod(long seconds) {
            return withGracePeriod(Duration.ofSeconds(seconds));
        }
    }
}
