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

package org.axonframework.examples.demo.coursecatalog.catalog.testutil;

import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fluent harness for running a full {@link EventTransformerChain} on a test input.
 * Mirrors {@link TransformationTester}'s shape; use this when verifying multi-hop
 * scenarios or full-chain behaviour rather than a single transformation.
 */
public final class ChainTester {

    private final EventTransformerChain chain;
    private MessageConverter converter = new DelegatingMessageConverter(new JacksonConverter());
    private MessageTypeResolver typeResolver = cls -> Optional.empty();

    private ChainTester(EventTransformerChain chain) {
        this.chain = chain;
    }

    /**
     * @param chain the chain under test
     * @return a new tester targeting {@code chain}
     */
    public static ChainTester forChain(EventTransformerChain chain) {
        return new ChainTester(chain);
    }

    /**
     * @param converter overrides the default Jackson {@link MessageConverter}
     * @return this tester
     */
    public ChainTester usingConverter(MessageConverter converter) {
        this.converter = converter;
        return this;
    }

    /**
     * @param resolver overrides the default empty {@link MessageTypeResolver}
     * @return this tester
     */
    public ChainTester usingTypeResolver(MessageTypeResolver resolver) {
        this.typeResolver = resolver;
        return this;
    }

    /** @return the {@code given()} builder collecting input data */
    public Given given() {
        return new Given();
    }

    /** Builder collecting the input event under test. */
    public final class Given {
        private MessageType inputType;
        private Object inputPayload;

        /**
         * @param qualifiedName qualified name of the input event
         * @param version       version string
         * @return this builder
         */
        public Given messageType(String qualifiedName, String version) {
            this.inputType = new MessageType(qualifiedName, version);
            return this;
        }

        /**
         * @param payload the input payload
         * @return this builder
         */
        public Given payload(Object payload) {
            this.inputPayload = payload;
            return this;
        }

        /**
         * @param resourcePath classpath-relative path to a golden JSON resource
         * @return this builder
         */
        public Given payloadFromResource(String resourcePath) {
            this.inputPayload = JsonAssertions.loadJson(resourcePath);
            return this;
        }

        /** @return the {@code when} stage holding the chain result */
        public When whenChainApplied() {
            if (inputType == null) {
                throw new IllegalStateException("given().messageType(...) was not set");
            }
            if (inputPayload == null) {
                throw new IllegalStateException("given().payload(...) or .payloadFromResource(...) was not set");
            }
            EventMessage input = new GenericEventMessage(inputType, inputPayload);
            List<EventMessage> outputs = collect(chain.transform(
                    MessageStream.fromIterable(List.of(input)),
                    null,
                    converter,
                    typeResolver
            ));
            return new When(outputs);
        }
    }

    /** Holds the chain's output(s) for assertions in the test. */
    public static final class When {
        private final List<EventMessage> outputs;

        private When(List<EventMessage> outputs) {
            this.outputs = outputs;
        }

        /**
         * @return the single output event
         * @throws AssertionError if the chain produced more or fewer than one event
         */
        public EventMessage output() {
            if (outputs.size() != 1) {
                throw new AssertionError("Expected exactly one output event but got " + outputs.size());
            }
            return outputs.getFirst();
        }

        /** @return all output events */
        public List<EventMessage> outputs() {
            return List.copyOf(outputs);
        }
    }

    private static List<EventMessage> collect(MessageStream<? extends EventMessage> stream) {
        List<EventMessage> collected = new ArrayList<>();
        stream.<Void>reduce(null, (acc, entry) -> {
            collected.add(entry.message());
            return null;
        }).join();
        return collected;
    }
}
