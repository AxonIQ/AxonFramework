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

package org.axonframework.integrationtests.queryhandling;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.queryhandling.SubscriptionQueryAlreadyRegisteredException;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An {@link AbstractSubscriptionQueryTestSuite} implementation validating the {@link SimpleQueryBus}.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 */
public class SimpleQueryBusSubscriptionQueryTest extends AbstractSubscriptionQueryTestSuite {

    private final Configuration config = createMessagingConfigurer().build();

    @Override
    public QueryBus queryBus() {
        return config.getComponent(QueryBus.class);
    }

    @Override
    protected MessagingConfigurer createMessagingConfigurer() {
        return MessagingConfigurer.create();
    }

    //fixme: SimpleQueryBus throws for duplicated subscriptions, how it should work with AxonServer?
    @Test
    void doubleSubscriptionMessage() {
        // given
        QueryMessage queryMessage = new GenericQueryMessage(
                CHAT_MESSAGES_QUERY_TYPE, TEST_QUERY_PAYLOAD
        );

        // when
        queryBus.subscriptionQuery(queryMessage, null, 50);
        MessageStream<QueryResponseMessage> secondSubscription = queryBus.subscriptionQuery(queryMessage, null, 50);

        // then
        assertTrue(secondSubscription.error().isPresent());
        assertInstanceOf(SubscriptionQueryAlreadyRegisteredException.class, secondSubscription.error().get());
    }
}
