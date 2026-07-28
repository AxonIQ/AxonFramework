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

package org.axonframework.hunt.harness;

import io.axoniq.axonserver.connector.AxonServerConnection;
import io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.jspecify.annotations.Nullable;

/**
 * The published Axon Server storage engine, made loadable against this reactor by implementing the one abstract method
 * it does not.
 * <p>
 * <b>This is a test adapter, not a patch.</b> No framework class and no connector class is modified; the harness
 * subclasses the published engine exactly as it subclasses and wraps every other store it drives. The reason it is
 * needed is a version skew that the compiler cannot see:
 * {@link EventStorageEngine#source(SourcingCondition, ProcessingContext)} is abstract in this reactor and the
 * single-argument form is a {@code default} delegating to it, while every published connector -- 5.1.2, 5.2.1 and
 * 5.2.2 alike -- implements only the single-argument form. {@code javac} accepts a two-argument call because it resolves
 * against the interface; the JVM then refuses it:
 * <pre>{@code
 * java.lang.AbstractMethodError: Receiver class
 *   io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine
 * does not define or inherit an implementation of the resolved method
 *   'abstract org.axonframework.messaging.core.MessageStream source(
 *      org.axonframework.eventsourcing.eventstore.SourcingCondition,
 *      org.axonframework.messaging.core.unitofwork.ProcessingContext)'
 * of interface org.axonframework.eventsourcing.eventstore.EventStorageEngine.
 * }</pre>
 * <b>Exactly one method is shimmed, and this is precisely what it does and does not model.</b> It drops the
 * {@link ProcessingContext} and calls the connector's own single-argument implementation. The interface's own Javadoc
 * says the two forms "behave identically" and that the context is "passed to decorators (for example snapshot-loading
 * and tracing decorators) that need to correlate the sourcing operation with the surrounding unit of work", so a leaf
 * engine that ignores it reads events exactly as the connector reads them. What is therefore <b>not</b> modelled is any
 * behaviour a future connector might derive from the context itself: correlating a sourcing with the unit of work that
 * asked for it, joining a server-side transaction, or attaching sourcing telemetry to the surrounding trace. A finding
 * that could be explained by the absent context is not a finding on this arm.
 * <p>
 * <b>The connector's snapshot store is in the same position and is deliberately not shimmed either, but it is not a
 * stub.</b> {@code AxonServerSnapshotStore} is fully implemented, with working public bodies for
 * {@code store(QualifiedName, Object, Snapshot)} and {@code load(QualifiedName, Object)}; what it lacks is the
 * context-carrying overloads. That half of the skew is the harder one, because
 * {@link org.axonframework.eventsourcing.snapshot.store.SnapshotStore} declares <em>only</em> the context-carrying forms
 * with no {@code default} to fall back on, so the connector's two methods override nothing at all. A shim there would
 * still work -- the bodies exist -- and it is not written because no scenario in this suite loads or stores a snapshot,
 * and shimming a method nothing exercises would model something nothing measures. The full set of four, and which of them
 * is shimmed, is recorded in {@code formal/CONNECTOR-COMPATIBILITY.md} and in {@code formal/FINDINGS.adoc} under F-18.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
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
     * <p>
     * The shimmed method. See the class Javadoc for exactly what dropping the context does and does not model.
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
