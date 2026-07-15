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

package org.axonframework.examples.demo.multitenancy.scaffolding;

import io.axoniq.axonserver.connector.admin.AdminChannel;
import io.axoniq.axonserver.grpc.admin.ContextOverview;
import io.axoniq.axonserver.grpc.admin.CreateContextRequest;
import io.axoniq.axonserver.grpc.admin.DeleteContextRequest;
import io.axoniq.framework.axonserver.connector.api.AxonServerConnectionManager;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static io.axoniq.framework.axonserver.connector.api.AxonServerConfiguration.ADMIN_CONTEXT;
import static io.axoniq.framework.axonserver.connector.api.AxonServerConfiguration.DEFAULT_REPLICATION_GROUP;

/**
 * Creates and deletes Axon Server contexts through the Admin API, so the demo can provision one
 * context per tenant and add or remove tenants at runtime.
 * <p>
 * Each tenant of the Axon Server-backed run is a real Axon Server context. This helper wraps the
 * {@link AxonServerConnectionManager}'s {@link AdminChannel admin channel} to keep that provisioning
 * out of the application flow. Managing contexts requires a multi-context (Enterprise Edition) Axon
 * Server, so this helper is only used on the Axon Server-backed path.
 */
public final class AxonServerTenantContexts {

    private static final Logger logger = LoggerFactory.getLogger(AxonServerTenantContexts.class);

    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(30);

    private final AxonServerConnectionManager connectionManager;

    /**
     * Constructs a helper managing contexts through the given {@code connectionManager}.
     *
     * @param connectionManager the connection manager whose admin channel provisions the contexts
     */
    public AxonServerTenantContexts(AxonServerConnectionManager connectionManager) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "The connection manager must not be null");
    }

    /**
     * Creates the given {@code context} as a tenant context when Axon Server does not know it yet, and
     * waits until it is visible. A context that already exists is left untouched, so running the demo
     * repeatedly is safe.
     *
     * @param context the name of the context to create
     */
    public void createContextIfAbsent(String context) {
        if (contexts().contains(context)) {
            logger.info("Context [{}] already exists, reusing it.", context);
            return;
        }
        logger.info("Creating context [{}].", context);
        adminChannel().createContext(CreateContextRequest.newBuilder()
                                                         .setName(context)
                                                         .setReplicationGroupName(DEFAULT_REPLICATION_GROUP)
                                                         .setDcbContext(true)
                                                         .build())
                      .orTimeout(ADMIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                      .join();
        Awaitility.await("context [" + context + "] created")
                  .atMost(ADMIN_TIMEOUT)
                  .until(() -> contexts().contains(context));
    }

    /**
     * Deletes the given {@code context} and waits until it is gone. Deleting a tenant's context removes
     * its Axon Server data, matching the demo's tenant-removal step.
     *
     * @param context the name of the context to delete
     */
    public void deleteContext(String context) {
        logger.info("Deleting context [{}].", context);
        adminChannel().deleteContext(DeleteContextRequest.newBuilder()
                                                         .setName(context)
                                                         .build())
                      .orTimeout(ADMIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                      .join();
        Awaitility.await("context [" + context + "] deleted")
                  .atMost(ADMIN_TIMEOUT)
                  .until(() -> !contexts().contains(context));
    }

    private List<String> contexts() {
        return adminChannel().getAllContexts()
                             .orTimeout(ADMIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                             .join()
                             .stream()
                             .map(ContextOverview::getName)
                             .toList();
    }

    private AdminChannel adminChannel() {
        return connectionManager.getConnection(ADMIN_CONTEXT).adminChannel();
    }
}
