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

package org.axonframework.examples.demo.coursecatalog.catalog.write;

import org.axonframework.examples.demo.coursecatalog.shared.region.RequestRegion;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

/**
 * Lifts the request region from a command's {@link RequestRegion#METADATA_KEY metadata} onto
 * the active {@link ProcessingContext}, modeling the edge where a region is attached once per
 * request from the caller's identity.
 * <p>
 * Because the resource is attached before the handler is invoked, it is in place by the time
 * the handler sources an entity and the transformation chain runs, so
 * {@link org.axonframework.examples.demo.coursecatalog.catalog.transformations.StudentRegisteredV2ToV3}
 * can backfill the region of historic events. Commands that
 * carry no region leave the context untouched, falling back to {@link RequestRegion#UNKNOWN_REGION}.
 */
public final class RequestRegionCommandInterceptor implements MessageHandlerInterceptor<CommandMessage> {

    @Override
    public MessageStream<?> interceptOnHandle(CommandMessage message,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<CommandMessage> interceptorChain) {
        String region = message.metadata().get(RequestRegion.METADATA_KEY);
        if (region != null) {
            context.putResource(RequestRegion.RESOURCE_KEY, region);
        }
        return interceptorChain.proceed(message, context);
    }
}
