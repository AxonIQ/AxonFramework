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
package queries.querydispatchers.shutdown;

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;

class TrackingCallSiteExample {

    private QueryGateway queryGateway;
    private QueryBus queryBus;

    // tag::track-call-sites[]
    @Autowired
    QueryShutdownManager shutdownManager;

    // Via QueryGateway (returns Publisher<T>):
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Publisher<MyDto> stream() {
        return shutdownManager.track( // <1>
            queryGateway.subscriptionQuery(new MyQuery(), MyDto.class)
        );
    }

    // Via QueryBus directly (returns MessageStream<T>):
    public MessageStream<QueryResponseMessage> streamViaBus(QueryMessage queryMessage) {
        return shutdownManager.track(
            queryBus.subscriptionQuery(queryMessage, null, 100) // <2>
        );
    }
    // end::track-call-sites[]
}
