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
package messagingconcepts.processingcontext;

import java.util.concurrent.CompletableFuture;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LifecycleActionRegistration {

    private static final Logger logger = LoggerFactory.getLogger(LifecycleActionRegistration.class);

    private final Object order = new Object();
    private final NotificationService notificationService = new NotificationService();

    // tag::register-lifecycle-actions[]
    @CommandHandler
    public void handle(CreateOrderCommand command,
                       ProcessingContext context) {
        // Asynchronous action registration...
        context.onCommit(
                ctx -> saveToDatabase(order).thenApply(result -> {
                    logger.info("Order saved: {}", result);
                    return null;
                })
        );
        // Synchronous action registration...
        context.runOnAfterCommit(
                ctx -> notificationService.sendOrderConfirmation(command.orderId())
        );
    }
    // end::register-lifecycle-actions[]

    // tag::phase-handlers[]
    public void registerPhaseHandlers(ProcessingContext context) {
        // Async variants
        context.onPreInvocation(ctx -> doAsyncSetup());
        context.onInvocation(ctx -> handleAsync());
        context.onPostInvocation(ctx -> doAsyncPostProcessing());
        context.onPrepareCommit(ctx -> validateBeforeCommit());
        context.onCommit(ctx -> commitTransaction());
        context.onAfterCommit(ctx -> sendNotifications());

        // Sync variants
        context.runOnPreInvocation(ctx -> doSetup());
        context.runOnInvocation(ctx -> handleSync());
        context.runOnPostInvocation(ctx -> doPostProcessing());
        context.runOnPrepareCommit(ctx -> validate());
        context.runOnCommit(ctx -> commit());
        context.runOnAfterCommit(ctx -> notify());
    }
    // end::phase-handlers[]

    // tag::sync-result-in-future[]
    public void registerSyncResultHandlerInFuture(ProcessingContext context) {
        context.onCommit(ctx -> {
            String result = performSyncOperation();
            return CompletableFuture.completedFuture(result);
        });
    }
    // end::sync-result-in-future[]

    private CompletableFuture<String> saveToDatabase(Object order) {
        return CompletableFuture.completedFuture("saved");
    }

    private String performSyncOperation() {
        return "result";
    }

    private CompletableFuture<?> doAsyncSetup() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<?> handleAsync() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<?> doAsyncPostProcessing() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<?> validateBeforeCommit() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<?> commitTransaction() {
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<?> sendNotifications() {
        return CompletableFuture.completedFuture(null);
    }

    private void doSetup() {
        // Prepare resources before invocation.
    }

    private void handleSync() {
        // Perform the main synchronous work.
    }

    private void doPostProcessing() {
        // Run post-processing logic.
    }

    private void validate() {
        // Validate before commit.
    }

    private void commit() {
        // Commit the changes.
    }
}
