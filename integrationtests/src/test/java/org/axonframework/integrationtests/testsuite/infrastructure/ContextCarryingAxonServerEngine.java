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

package org.axonframework.integrationtests.testsuite.infrastructure;

import io.axoniq.axonserver.connector.AxonServerConnection;
import io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine;
import org.axonframework.common.annotation.Internal;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.jspecify.annotations.Nullable;

/**
 * The published Axon Server storage engine, made loadable against this reactor by implementing the one abstract method it
 * does not.
 * <p>
 * <b>This is a test adapter, not a patch.</b> No framework class and no connector class is modified. The reason it is
 * needed is a version skew the compiler cannot see:
 * {@link EventStorageEngine#source(SourcingCondition, ProcessingContext)} is abstract in this reactor and the
 * single-argument form is a {@code default} delegating to it, while every published connector implements only the
 * single-argument form. A two-argument call compiles, because it resolves against the interface, and then fails at run
 * time with {@code AbstractMethodError}.
 * <p>
 * <b>Exactly one method is shimmed, and this is what it does and does not model.</b> It drops the
 * {@link ProcessingContext} and calls the connector's own single-argument implementation. The interface's own Javadoc says
 * the two forms behave identically and that the context is passed to decorators that correlate a sourcing with the
 * surrounding unit of work, so a leaf engine that ignores it reads events exactly as the connector reads them. What is
 * therefore not modelled is any behaviour a future connector might derive from the context itself.
 * <p>
 * <b>This class is duplicated from the hunt simulation module on purpose.</b> That module is behind a Maven profile and
 * this one is not, so depending on it would put the hunt harness on this module's build path. The two copies are
 * intentionally identical, and {@code formal/CONNECTOR-COMPATIBILITY.md} is the single place recording which methods are
 * shimmed and why.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@Internal
public class ContextCarryingAxonServerEngine extends AxonServerEventStorageEngine {

    /**
     * Constructs an engine over the given connection.
     *
     * @param connection the connection to the Axon Server context this engine reads and writes
     * @param converter  the converter turning an event message into the wire form and back
     */
    public ContextCarryingAxonServerEngine(AxonServerConnection connection, EventConverter converter) {
        super(connection, converter);
    }

    /**
     * Sources events matching the given {@code condition}, ignoring the {@code context}.
     *
     * @param condition the condition dictating which events to source
     * @param context   the processing context active while sourcing, which this engine does not use
     * @return a finite stream of the events matching the condition
     */
    @Override
    public MessageStream<EventMessage> source(SourcingCondition condition, @Nullable ProcessingContext context) {
        return super.source(condition);
    }
}
