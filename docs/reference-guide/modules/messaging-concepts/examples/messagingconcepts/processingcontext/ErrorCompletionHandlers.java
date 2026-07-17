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

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ErrorCompletionHandlers {

    private static final Logger logger = LoggerFactory.getLogger(ErrorCompletionHandlers.class);

    // tag::error-completion-handlers[]
    @EventHandler
    public void on(OrderPlacedEvent event, ProcessingContext context) {
        // Register error handler - invoked when any phase fails
        context.onError((ctx, phase, error) -> {
            logger.error("Processing failed in phase {}: {}", phase, error.getMessage());
            rollbackChanges();
            publishErrorEvent(event, error);
        });

        // Register completion handler - invoked when all phases succeed
        context.whenComplete(ctx -> {
            logger.info("Processing completed successfully");
            updateMetrics();
        });

        // Register finally handler - invoked regardless of success or failure
        context.doFinally(ctx -> {
            releaseResources();
            cleanupTempFiles();
        });

        processEvent(event);
    }
    // end::error-completion-handlers[]

    private void rollbackChanges() {
        // Undo changes made before the failure.
    }

    private void publishErrorEvent(OrderPlacedEvent event, Throwable error) {
        // Publish a compensating or error event.
    }

    private void updateMetrics() {
        // Record success metrics.
    }

    private void releaseResources() {
        // Free any resources acquired during processing.
    }

    private void cleanupTempFiles() {
        // Remove temporary files.
    }

    private void processEvent(OrderPlacedEvent event) {
        // Update the read model with the placed order.
    }
}
