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
package messagingconcepts.componentmessageintercepting.genericall;

import messagingconcepts.componentmessageintercepting.CardIssuedEvent;
import messagingconcepts.componentmessageintercepting.CardSummaryView;
import messagingconcepts.componentmessageintercepting.FindCardSummaryQuery;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::message-interceptor-all[]
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class CardSummaryProjection {

    @MessageHandlerInterceptor
    void interceptAll(Message message) {
        // Runs before every @EventHandler and @QueryHandler on this component
    }

    @EventHandler
    void on(CardIssuedEvent event, ProcessingContext context) {
        // Handle event
    }

    @QueryHandler
    CardSummaryView handle(FindCardSummaryQuery query, ProcessingContext context) {
        // Handle query
        return null;
    }
}
// end::message-interceptor-all[]
