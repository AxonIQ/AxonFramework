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
package monitoring.metrics.monitorfactory;

// tag::event-processor-monitor-factory[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.monitoring.MessageMonitor;

class AxonConfig {

    public void registerEventProcessorMonitorFactory(MessagingConfigurer configurer) {
        configurer.registerEventMonitor((config, componentType, componentName) -> {
            // Only create a monitor for EventProcessors with a specific name
            if (EventProcessor.class.isAssignableFrom(componentType)
                    && "my-processor".equals(componentName)) {
                return new MyCustomEventMonitor();
            }
            // Return null to skip monitoring for other components
            return null;
        });
    }
}
// end::event-processor-monitor-factory[]

class MyCustomEventMonitor implements MessageMonitor<EventMessage> {

    @Override
    public MessageMonitor.MonitorCallback onMessageIngested(EventMessage message) {
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
