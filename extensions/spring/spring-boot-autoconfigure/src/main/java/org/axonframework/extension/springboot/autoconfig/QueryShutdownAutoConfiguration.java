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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Auto-configuration that integrates {@link QueryShutdownManager} beans with Spring's lifecycle.
 * <p>
 * When at least one {@link QueryShutdownManager} bean is present, this configuration registers a
 * {@link SmartLifecycle} that calls {@link QueryShutdownManager#shutdown()} on all managers
 * during application shutdown.
 * <p>
 * Running at {@code DEFAULT_PHASE - 1024}, the same phase as Spring Boot's web server graceful
 * shutdown, ensures that subscription queries are cancelled at the same moment the web server
 * stops accepting new connections. This prevents a window where new SSE requests could arrive
 * after cancellation but before the web server rejects them, and allows the web server's drain
 * wait to complete quickly because active queries are being closed simultaneously.
 * <p>
 * This auto-configuration only handles lifecycle management. Gateway-level tracking, where every
 * subscription or streaming query dispatched through a {@link org.axonframework.messaging.queryhandling.gateway.QueryGateway}
 * is automatically registered with a manager, requires explicit configuration. Use
 * {@link QueryShutdownManager#track} at individual call sites, or configure a named gateway with
 * {@link org.axonframework.messaging.core.configuration.MessagingConfigurer#queryGateway} for
 * automatic per-gateway tracking.
 * <p>
 * Example:
 * <pre>{@code
 * @Bean
 * public QueryShutdownManager queryShutdownManager() {
 *     return QueryShutdownManager.closeImmediately();
 * }
 * }</pre>
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
@AutoConfiguration
@AutoConfigureAfter(AxonAutoConfiguration.class)
public class QueryShutdownAutoConfiguration {

    /**
     * Creates a {@link SmartLifecycle} that calls {@link QueryShutdownManager#shutdown()} on all
     * registered {@link QueryShutdownManager} beans during application shutdown.
     * <p>
     * Runs at {@code DEFAULT_PHASE - 1024}, the same phase as Spring Boot's web server graceful
     * shutdown, so subscription queries are cancelled at the same moment the web server stops
     * accepting new connections.
     *
     * @param managers all {@link QueryShutdownManager} beans in the application context
     * @return a lifecycle bean that shuts down all managers at the appropriate phase
     */
    @Bean
    @ConditionalOnBean(QueryShutdownManager.class)
    public SmartLifecycle queryShutdownManagerLifecycle(List<QueryShutdownManager> managers) {
        return new QueryShutdownManagerLifecycle(managers);
    }

    private static final class QueryShutdownManagerLifecycle implements SmartLifecycle {

        private final List<QueryShutdownManager> managers;
        private volatile boolean running = false;

        QueryShutdownManagerLifecycle(List<QueryShutdownManager> managers) {
            this.managers = managers;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop(Runnable callback) {
            CompletableFuture<?>[] futures = managers.stream()
                                                     .map(QueryShutdownManager::shutdown)
                                                     .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures)
                             .whenComplete((r, e) -> {
                                 running = false;
                                 callback.run();
                             });
        }

        @Override
        public void stop() {
            CompletableFuture<?>[] futures = managers.stream()
                                                     .map(QueryShutdownManager::shutdown)
                                                     .toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(futures)
                                 .orTimeout(30, TimeUnit.SECONDS)
                                 .join();
            } finally {
                running = false;
            }
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return SmartLifecycle.DEFAULT_PHASE - 1024;
        }

        @Override
        public boolean isAutoStartup() {
            return true;
        }
    }
}
