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
package configuration.spring;

// tag::message-handling-component-example[]

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * We have combined a command, event, and query handler in one class as an example only. Typically, these handlers are
 * separated.
 */
@Component
public class GiftCardHandler {

    private final Map<String, Integer> balances = new ConcurrentHashMap<>();

    @CommandHandler
    public void handle(RedeemGiftCardCommand command, EventAppender eventAppender) {
        eventAppender.append(new GiftCardRedeemedEvent(command.cardId(), command.amount()));
    }

    @EventHandler
    public void on(GiftCardRedeemedEvent event) {
        balances.merge(event.cardId(), -event.amount(), Integer::sum);
    }

    @QueryHandler
    public GiftCardBalance handle(FetchGiftCardBalanceQuery query) {
        return new GiftCardBalance(query.cardId(), balances.getOrDefault(query.cardId(), 0));
    }
}
// end::message-handling-component-example[]
