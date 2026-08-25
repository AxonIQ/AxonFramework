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

package org.axonframework.extension.springboot.compatibility;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.springframework.stereotype.Component;

/**
 * A single component-scanned command handler, present because virtually every real Axon application has one. Its
 * presence is what makes the context-loads test in each Spring Boot version module exercise Axon's actual
 * message-handling wiring (rather than only the JPA event store's auto-configuration in isolation), which is
 * load-bearing for reproducing bean-creation-order-sensitive Spring Boot compatibility issues.
 *
 * @author Jakob Hatzl
 * @since 5.4.0
 */
@Component
public class PingHandler {

    @CommandHandler
    public String handle(String command) {
        return "pong: " + command;
    }
}
