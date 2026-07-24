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
package events.eventprocessors.index.interceptors.exceptionhandler;

// The import block is indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::with-exception-handler-import[]
    import org.axonframework.messaging.core.MessageStream;
    import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;

// end::with-exception-handler-import[]
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ExceptionHandlingConfig {

    private final Logger log = LoggerFactory.getLogger(ExceptionHandlingConfig.class);

    // tag::with-exception-handler[]
    private EventHandlingComponentsConfigurer.CompletePhase configureHandlingComponents(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("orderHandler", cfg -> new OrderEventHandler())
                                  .withExceptionHandler(cfg -> (event, context, error) -> {
                                      log.warn("Handler failed for {}: {}", event.type().qualifiedName(), error.getMessage());
                                      return MessageStream.empty(); // suppress; use MessageStream.failed(error) to propagate
                                  });
    }
    // end::with-exception-handler[]
}

record OrderPlaced(String orderId) {

}

class OrderEventHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(OrderPlaced event) {
        // handle order placement
    }
}
