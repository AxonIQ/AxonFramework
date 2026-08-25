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

package org.axonframework.test.saga;

import org.axonframework.messaging.core.annotation.ParameterResolverFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Command payload dedicated to triggering the resolution of parameters through a custom
 * {@link ParameterResolverFactory}.
 *
 * @author Steven van Beelen
 */
public class ResolveParameterCommand {

    private final Object identifier;
    private final AtomicBoolean assertion;

    ResolveParameterCommand(Object identifier, AtomicBoolean assertion) {
        this.identifier = identifier;
        this.assertion = assertion;
    }

    public Object getIdentifier() {
        return identifier;
    }

    public AtomicBoolean getAssertion() {
        return assertion;
    }
}
