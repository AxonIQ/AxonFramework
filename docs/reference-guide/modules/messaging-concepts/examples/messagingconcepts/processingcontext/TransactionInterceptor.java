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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::transaction-interceptor[]
// Note that this is an advanced example!
// Axon Framework typically deals with transaction and connection details for you, so you don't have to.
// That makes this example useful ONLY if you are using a storage solution that is not supported by Axon Framework out of the box.
public class TransactionInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    private static final ResourceKey<Transaction> TX_KEY = ResourceKey.withLabel("Transaction");
    // end::transaction-interceptor[]

    private final TransactionManager transactionManager = new TransactionManager();

    // tag::transaction-interceptor[]

    @Override
    public MessageStream<?> interceptOnHandle(
            CommandMessage command,
            ProcessingContext context,
            MessageHandlerInterceptorChain<CommandMessage> chain
    ) {
        // Create resource in pre-invocation
        context.runOnPreInvocation(ctx -> {
            Transaction tx = transactionManager.beginTransaction();
            ctx.putResource(TX_KEY, tx);
        });

        // Commit in commit phase
        context.onCommit(ctx -> {
            Transaction tx = ctx.getResource(TX_KEY);
            return tx.commitAsync();
        });

        // Rollback on error
        context.onError((ctx, phase, error) -> {
            Transaction tx = ctx.getResource(TX_KEY);
            if (tx != null) {
                tx.rollback();
            }
        });

        // Cleanup in finally
        context.doFinally(ctx -> {
            Transaction tx = ctx.removeResource(TX_KEY);
            if (tx != null) {
                tx.close();
            }
        });

        return chain.proceed(command, context);
    }
}
// end::transaction-interceptor[]
