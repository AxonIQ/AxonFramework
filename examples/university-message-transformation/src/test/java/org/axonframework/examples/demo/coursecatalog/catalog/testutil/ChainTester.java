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
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fluent harness for running a full {@link EventTransformerChain} on a test input.
 * Mirrors the {@code given -> when -> then} shape of the {@code AxonTestFixture};
 * use this for multi-hop scenarios or full-chain behavior, and
 * {@link TransformationTester} for single-transformation tests.
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

    /** @return the {@code given} phase collecting input data */
    public Given given() {
        return new Given();
    }

    /** Builder collecting the input event under test. */
    public final class Given {
        private @Nullable MessageType inputType;
        private @Nullable Object inputPayload;
        private Metadata inputMetadata = Metadata.emptyInstance();

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
         * @param resourcePath classpath-relative path to a fixture JSON resource
         * @return this builder
         */
        public Given payloadFromResource(String resourcePath) {
            this.inputPayload = JsonAssertions.loadJson(resourcePath);
            return this;
        }

        /**
         * @param metadata metadata to attach to the input event (defaults to empty)
         * @return this builder
         */
        public Given metadata(Metadata metadata) {
            this.inputMetadata = metadata;
            return this;
        }

        /** @return the {@code when} phase, after running the chain on the given input */
        public When when() {
            if (inputType == null) {
                throw new IllegalStateException("given().messageType(...) was not set");
            }
            if (inputPayload == null) {
                throw new IllegalStateException("given().payload(...) or .payloadFromResource(...) was not set");
            }
            return new When(TransformationOutcome.run(
                    chain, inputType, inputPayload, inputMetadata, converter, typeResolver));
        }
    }

    /** Holds the chain's outcome. Use {@link #then()} to assert against it. */
    public static final class When {
        private final TransformationOutcome outcome;

        private When(TransformationOutcome outcome) {
            this.outcome = outcome;
        }

        /** @return the {@code then} phase exposing chainable assertions */
        public Then then() {
            return new Then(outcome);
        }
    }

    /**
     * Chainable assertions on the chain's outcome. Each method either succeeds and
     * returns {@code this} for further chaining, or fails the test by throwing an
     * {@link AssertionError}.
     */
    public static final class Then {
        private final TransformationOutcome outcome;

        private Then(TransformationOutcome outcome) {
            this.outcome = outcome;
        }

        /** @return this, after asserting no exception, was thrown */
        public Then success() {
            outcome.requireSuccess();
            return this;
        }

        /** @return this, after asserting exactly zero output events */
        public Then noOutput() {
            outcome.requireSuccess();
            assertThat(outcome.outputs()).as("chain output").isEmpty();
            return this;
        }

        /** @return this, after asserting exactly one output event */
        public Then singleOutput() {
            outcome.requireSuccess();
            assertThat(outcome.outputs()).as("chain output").hasSize(1);
            return this;
        }

        /**
         * Asserts the single output event's type matches.
         *
         * @param expected the expected {@link MessageType}
         * @return this
         */
        public Then outputType(MessageType expected) {
            singleOutput();
            assertThat(outcome.outputs().getFirst().type()).as("output type").isEqualTo(expected);
            return this;
        }

        /**
         * Asserts the single output event's payload equals the expected value.
         *
         * @param expected the expected payload
         * @return this
         */
        public Then outputPayload(Object expected) {
            singleOutput();
            assertThat(outcome.outputs().getFirst().payload()).as("output payload").isEqualTo(expected);
            return this;
        }

        /**
         * Asserts the single output event's payload equals the JSON at the given resource.
         *
         * @param resourcePath classpath-relative path to a fixture JSON resource
         * @return this
         */
        public Then outputPayloadFromResource(String resourcePath) {
            return outputPayload(JsonAssertions.loadJson(resourcePath));
        }

        /**
         * Asserts that the single output event has the same identifier as the input
         * event, proving the chain returned the original message rather than constructing
         * a new one. Useful for verifying pass-through semantics.
         *
         * @return this
         */
        public Then outputPreservesInputIdentifier() {
            singleOutput();
            assertThat(outcome.outputs().getFirst().identifier())
                    .as("output identifier should equal input identifier (pass-through)")
                    .isEqualTo(outcome.input().identifier());
            return this;
        }

        /**
         * Asserts that the single output event's metadata equals the input event's
         * metadata, proving the chain preserves the envelope when rewriting the payload.
         *
         * @return this
         */
        public Then outputPreservesInputMetadata() {
            singleOutput();
            assertThat(outcome.outputs().getFirst().metadata())
                    .as("output metadata should equal input metadata")
                    .isEqualTo(outcome.input().metadata());
            return this;
        }

        /**
         * Asserts the single output event's payload structurally matches the JSON at the
         * given resource. The actual payload is converted to a {@code JsonNode} first, so
         * the comparison works even when the mapper returns a {@code Map} or other
         * non-{@code JsonNode} representation.
         *
         * @param resourcePath classpath-relative path to a fixture JSON resource
         * @return this
         */
        public Then outputPayloadStructurallyEquals(String resourcePath) {
            singleOutput();
            assertThat(JsonAssertions.toJsonTree(Objects.requireNonNull(outcome.outputs().getFirst().payload())))
                    .as("output payload (structural)")
                    .isEqualTo(JsonAssertions.loadJson(resourcePath));
            return this;
        }

        /**
         * Asserts an exception was thrown and runs the given consumer against it.
         *
         * @param assertion AssertJ-style assertion on the captured throwable
         * @return this
         */
        public Then exceptionSatisfies(Consumer<Throwable> assertion) {
            assertion.accept(outcome.requireException());
            return this;
        }

        /**
         * Escape hatch for ad-hoc assertions.
         *
         * @return the single output event
         */
        public EventMessage output() {
            singleOutput();
            return outcome.outputs().getFirst();
        }

        /**
         * Escape hatch for ad-hoc assertions.
         *
         * @return all output events
         */
        public List<EventMessage> outputs() {
            outcome.requireSuccess();
            return outcome.outputs();
        }
    }
}
