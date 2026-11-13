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

package org.axonframework.common.configuration;

import org.junit.jupiter.api.*;

import static org.axonframework.common.configuration.CapabilityPreservingDecorator.preservingCapabilitiesOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Reproduces, and then fixes, the narrowing bug described on AxonIQ/axoniq-framework#397 and
 * AxonIQ/AxonFramework#5039: a component registered under a single, most-specific type (mirroring how
 * {@code EventStore} is registered) gets narrowed down when a decorator written against a broader, assignable type
 * (mirroring an {@code EventBus}/{@code EventSink} tracing decorator) returns something that only implements that
 * broader type.
 */
class CapabilityPreservingDecoratorTest {

    /** Mirrors {@code EventSink} / {@code EventBus}: the narrower type a generic decorator is written against. */
    interface Publisher {

        String publish();
    }

    /** Mirrors {@code EventStore}: a wider type extending the narrower one, registered as the actual component. */
    interface Store extends Publisher {

        String open();
    }

    static class StoreImpl implements Store {

        @Override
        public String publish() {
            return "published";
        }

        @Override
        public String open() {
            return "opened";
        }
    }

    /** Mirrors a hand-written {@code TracingEventBus}: narrows its delegate down to just {@code Publisher}. */
    static class TracingPublisher implements Publisher {

        private final Publisher delegate;

        TracingPublisher(Publisher delegate) {
            this.delegate = delegate;
        }

        @Override
        public String publish() {
            return "traced(" + delegate.publish() + ")";
        }
    }

    private DefaultComponentRegistry testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new DefaultComponentRegistry();
        testSubject.registerComponent(Store.class, c -> new StoreImpl());
    }

    @Test
    void undecoratedComponentIsBothAPublisherAndAStore() {
        Configuration config = testSubject.build(mock());

        assertEquals("published", config.getComponent(Publisher.class).publish());
        assertEquals("opened", config.getComponent(Store.class).open());
    }

    @Test
    void plainDecoratorRegisteredForTheNarrowerTypeAbortsResolutionEntirely() {
        // A decorator written only against Publisher.class -- unaware Store even exists -- still matches
        // and applies to the Store.class-registered component, because matching is assignability-based.
        testSubject.registerDecorator(Publisher.class, 0, (config, name, delegate) -> new TracingPublisher(delegate));

        Configuration config = testSubject.build(mock());

        // DecoratedComponent#doResolve validates the decorator's output against the ORIGINAL registered
        // identifier's type (Store), not against whichever type the caller queried through. Since there is
        // only one underlying Component either way, even asking for just the narrower Publisher view blows
        // up eagerly -- exactly what TracingEventStore's own javadoc warns happens for the real EventStore
        // case ("Wrapping an EventStore in a plain TracingEventSink ... would fail that assignment check and
        // abort configuration").
        assertThrows(ClassCastException.class, () -> config.getComponent(Publisher.class));
    }

    @Test
    void wrappingTheDecoratorPreservesTheWiderTypeWithoutChangingItsOwnBehavior() {
        testSubject.registerDecorator(
                Publisher.class, 0,
                preservingCapabilitiesOf((config, name, delegate) -> new TracingPublisher(delegate))
        );

        Configuration config = testSubject.build(mock());

        // The decoration itself still applies...
        assertEquals("traced(published)", config.getComponent(Publisher.class).publish());
        // ...but Store's own methods, which TracingPublisher knows nothing about, still work, routed
        // straight to the raw, undecorated delegate.
        assertEquals("opened", config.getComponent(Store.class).open());
    }

    @Test
    void bothViewsResolveToTheSameSingleUnderlyingComponent() {
        testSubject.registerDecorator(
                Publisher.class, 0,
                preservingCapabilitiesOf((config, name, delegate) -> new TracingPublisher(delegate))
        );

        Configuration config = testSubject.build(mock());

        // There was only ever one Identifier (Store.class) registered, so both views necessarily resolve
        // through the very same Component -- confirmed here by pointer identity, not just behavior.
        assertSame(config.getComponent(Publisher.class), config.getComponent(Store.class));
    }

    @Test
    void decoratorThatDoesNotNarrowIsReturnedUntouched() {
        // A decorator whose own output already implements everything the delegate did needs no proxying.
        testSubject.registerDecorator(
                Store.class, 0,
                preservingCapabilitiesOf((config, name, delegate) -> delegate)
        );

        Configuration config = testSubject.build(mock());

        assertSame(config.getComponent(Store.class), config.getComponent(Publisher.class));
    }
}
