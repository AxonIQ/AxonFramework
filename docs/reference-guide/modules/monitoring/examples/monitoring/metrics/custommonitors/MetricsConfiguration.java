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
package monitoring.metrics.custommonitors;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;

// tag::custom-message-monitors[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.monitoring.MessageMonitor;

class MetricsConfiguration {

    public void configureCustomMonitors(MessagingConfigurer configurer) {
        // Register a monitor for all message types
        configurer.registerMessageMonitor(config -> new MyCustomMessageMonitor());

        // Register monitors for specific message types
        configurer.registerCommandMonitor(config -> new MyCommandMonitor());
        configurer.registerEventMonitor(config -> new MyEventMonitor());
        configurer.registerQueryMonitor(config -> new MyQueryMonitor());
    }
}
// end::custom-message-monitors[]

class MyCustomMessageMonitor implements MessageMonitor<Message> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(Message message) {
        return NoOpCallback.INSTANCE;
    }
}

class MyCommandMonitor implements MessageMonitor<CommandMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(CommandMessage message) {
        return NoOpCallback.INSTANCE;
    }
}

class MyEventMonitor implements MessageMonitor<EventMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(EventMessage message) {
        return NoOpCallback.INSTANCE;
    }
}

class MyQueryMonitor implements MessageMonitor<QueryMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(QueryMessage message) {
        return NoOpCallback.INSTANCE;
    }
}

enum NoOpCallback implements MessageMonitor.MonitorCallback {
    INSTANCE;

    @Override
    public void reportSuccess() {
    }

    @Override
    public void reportFailure(Throwable cause) {
    }

    @Override
    public void reportIgnored() {
    }
}
