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

import io.axoniq.framework.messaging.transformation.events.EventTransformation;
import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
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
 * Fluent harness for invoking a single {@link EventTransformation} on a test input.
 * Mirrors the {@code given -> when -> then} shape of the {@code AxonTestFixture},
 * so transformation tests read the same as slice tests.
 *
 * <pre>{@code
 * TransformationTester.forTransformation(CoursePublishedV1ToV2.build())
 *     .given()
 *         .messageType(COURSE_PUBLISHED, "1.0.0")
 *         .payloadFromResource("/transformations/coursepublished/v1.json")
 *     .when()
 *     .then()
 *         .success()
 *         .outputType(new MessageType(COURSE_PUBLISHED, "2.0.0"))
 *         .outputPayloadFromResource("/transformations/coursepublished/v2.json");
 * }</pre>
 */
public final class TransformationTester {

    private final EventTransformation transformation;
    private MessageConverter converter = new DelegatingMessageConverter(new JacksonConverter());
    private MessageTypeResolver typeResolver = cls -> Optional.empty();

    private TransformationTester(EventTransformation transformation) {
        this.transformation = transformation;
    }

    /**
     * @param transformation the transformation under test
     * @return a new tester targeting {@code transformation}
     */
    public static TransformationTester forTransformation(EventTransformation transformation) {
        return new TransformationTester(transformation);
    }

    /**
     * @param converter overrides the default Jackson {@link MessageConverter}
     * @return this tester
     */
    public TransformationTester usingConverter(MessageConverter converter) {
        this.converter = converter;
        return this;
    }

    /**
     * @param resolver overrides the default empty {@link MessageTypeResolver}; the
     *                 chain treats {@link Optional#empty()} as "skip the output-identity
     *                 check"
     * @return this tester
     */
    public TransformationTester usingTypeResolver(MessageTypeResolver resolver) {
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
         * @param payload the input payload (typically a {@link tools.jackson.databind.JsonNode}
         *                or {@link java.util.Map})
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

        /** @return the {@code when} phase, after running the transformation on the given input */
        public When when() {
            if (inputType == null) {
                throw new IllegalStateException("given().messageType(...) was not set");
            }
            if (inputPayload == null) {
                throw new IllegalStateException("given().payload(...) or .payloadFromResource(...) was not set");
            }
            EventTransformerChain chain = EventTransformerChain.builder().register(transformation).build();
            return new When(TransformationOutcome.run(chain, inputType, inputPayload, converter, typeResolver));
        }
    }

    /** Holds the outcome of running the transformation. Use {@link #then()} to assert against it. */
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
     * Chainable assertions on the transformation's outcome. Each method either succeeds and
     * returns {@code this} for further chaining, or fails the test by throwing an
     * {@link AssertionError}.
     */
    public static final class Then {
        private final TransformationOutcome outcome;

        private Then(TransformationOutcome outcome) {
            this.outcome = outcome;
        }

        /** @return this, after asserting no exception was thrown */
        public Then success() {
            outcome.requireSuccess();
            return this;
        }

        /** @return this, after asserting exactly zero output events */
        public Then noOutput() {
            outcome.requireSuccess();
            assertThat(outcome.outputs()).as("transformation output").isEmpty();
            return this;
        }

        /** @return this, after asserting exactly one output event */
        public Then singleOutput() {
            outcome.requireSuccess();
            assertThat(outcome.outputs()).as("transformation output").hasSize(1);
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
