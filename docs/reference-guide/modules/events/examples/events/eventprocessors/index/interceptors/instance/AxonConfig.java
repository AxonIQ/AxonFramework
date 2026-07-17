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
package events.eventprocessors.index.interceptors.instance;

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;

// tag::processor-specific-interceptor[]
public class AxonConfig {

    public void registerProcessorSpecificInterceptor(MessagingConfigurer configurer) {
        // For SubscribingEventProcessor:
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.subscribing(
                subscribingConfigurer -> subscribingConfigurer.processor(
                        "my-processor",
                        config -> config.eventHandlingComponents(this::configureHandlingComponent)
                                        .customized((c, processorConfig) -> processorConfig.withInterceptor(
                                                new CustomEventHandlerInterceptor()
                                        ))
                )
        ));
        // For PooledStreamingEventProcessor:
        configurer.eventProcessing(eventConfigurer -> eventConfigurer.pooledStreaming(
                pooledStreamingConfigurer -> pooledStreamingConfigurer.processor(
                        "my-processor",
                        config -> config.eventHandlingComponents(this::configureHandlingComponent)
                                        .customized((c, processorConfig) -> processorConfig.withInterceptor(
                                                new CustomEventHandlerInterceptor()
                                        ))
                )
        ));
    }

    private EventHandlingComponentsConfigurer.AdditionalComponentPhase configureHandlingComponent(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("my-handler", c -> new MyHandler());
    }
}
// end::processor-specific-interceptor[]

class MyHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(MyEvent event) {
        // handle event
    }
}

record MyEvent(String id) {

}

class CustomEventHandlerInterceptor implements MessageHandlerInterceptor<EventMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(EventMessage message,
                                               ProcessingContext context,
                                               MessageHandlerInterceptorChain<EventMessage> interceptorChain) {
        return interceptorChain.proceed(message, context);
    }
}
