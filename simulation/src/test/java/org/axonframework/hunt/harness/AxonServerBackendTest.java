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

import io.axoniq.framework.axonserver.connector.event.AxonServerEventStorageEngine;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.hunt.checker.ModelConformanceChecker;
import org.axonframework.hunt.checker.OwnershipChecker;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.scenario.HuntScenarios;
import org.axonframework.hunt.scenario.Scenario;
import org.axonframework.hunt.scenario.ScenarioResult;
import org.axonframework.hunt.scenario.ScenarioRunner;
import org.axonframework.hunt.scenario.Tier;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Establishes three things about the Axon Server arm: that the version skew it works around is real, that a real server
 * genuinely serves the run, and that the arm inherits the shipped scenario corpus by registration alone.
 * <p>
 * <b>The first of the three is the one that matters most, and it is why this class exists rather than being folded into
 * the differential.</b> This arm links a released connector against a reactor the connector predates, and the harness
 * supplies one method the connector lacks. Any failure it produces could therefore be the skew rather than the framework,
 * and the only way to keep that honest is to have the skew's exact shape recorded as an executable fact: the unshimmed
 * engine raises {@code AbstractMethodError} on the method in question, and the shimmed one does not. A finding from this
 * arm has to be checked against that.
 * <p>
 * The compatibility question -- which methods a connector leaves unimplemented -- is answered without any container by
 * {@link ConnectorCompatibilityTest}, in about a second. This class answers the different question of whether the
 * combination actually works against a running server.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@org.junit.jupiter.api.Tag("container")
class AxonServerBackendTest {

    private static final AxonServerHuntBackend BACKEND = new AxonServerHuntBackend();

    private static DelegatingEventConverter converter() {
        return new DelegatingEventConverter(new JacksonConverter());
    }

    private static EventMessage event(String name) {
        return new GenericEventMessage(new MessageType(name, "0.0.1"), Map.of("amount", 10));
    }

    @Nested
    class TheVersionSkewThisArmWorksAround {

        @Test
        void isRealAndIsCoveredByExactlyTheOneMethodTheHarnessSupplies() {
            // given a connection to a real Axon Server, and the arm's label, which every verdict from it must carry
            System.out.println("ARM LABEL " + AxonServerHuntBackend.label());
            AxonServerHuntBackend.Deployment deployment = BACKEND.deployment();
            SourcingCondition condition = SourcingCondition.conditionFor(EventCriteria.havingAnyTag());

            try (AxonServerHuntBackend.Run run = AxonServerHuntBackend.Run.create(deployment)) {
                // when the published engine is asked to source with a processing context -- a call javac accepts,
                // because it resolves against the interface rather than against the class
                EventStorageEngine published =
                        new AxonServerEventStorageEngine(run.connection(), converter());

                // then the virtual machine refuses it, which is the whole reason this arm was recorded as blocked. The
                // error is asserted rather than described, so that the day a connector implements the method this test
                // fails and the shim can be deleted.
                assertThatThrownBy(() -> published.source(condition, null))
                        .as("the published connector must still lack the method the harness shims")
                        .isInstanceOf(AbstractMethodError.class)
                        .hasMessageContaining("does not define or inherit an implementation of the resolved method")
                        .hasMessageContaining("source");

                // and the harness's subclass answers the same call, because it implements it by delegating to the
                // connector's own single-argument form. That is the entire adaptation this arm rests on.
                EventStorageEngine shimmed = new ContextCarryingAxonServerEngine(run.connection(), converter());
                assertThat(shimmed.source(condition, null)
                                  .reduce(0L, (count, entry) -> count + 1)
                                  .orTimeout(60, TimeUnit.SECONDS)
                                  .join())
                        .as("the shimmed engine answers the call the published one refuses")
                        .isNotNegative();
            }
        }
    }

    @Nested
    class AnAxonServerThatActuallyServesTheRun {

        @Test
        void reportsItsContainerAndItsBoundaryContextAndRoundTripsAnAppendThroughBoth() {
            // given the shared server this arm drives
            AxonServerHuntBackend.Deployment deployment = BACKEND.deployment();
            System.out.println("AXON SERVER container=" + BACKEND.containerId()
                                       + " grpc=" + deployment.grpcHost() + ":" + deployment.grpcPort()
                                       + " admin=" + deployment.adminBase());
            String contexts = AxonServerHuntBackend.contexts(deployment);
            System.out.println("AXON SERVER contexts " + contexts);

            // then the context the arm drives exists. The server's own log line "Creating DCB context: default" is what
            // the container's wait strategy waited for, so a run only reaches this point on a server whose context is a
            // Dynamic Consistency Boundary one; the administration API is asked here for the reader's benefit.
            assertThat(contexts)
                    .as("the administration API must report the context the arm drives")
                    .contains("\"context\":\"" + AxonServerHuntBackend.CONTEXT + "\"");

            // when a boundary-shaped batch is appended and sourced back by one of its tags
            EventStorageEngine engine = BACKEND.createEngine();
            try {
                String account = "acct-" + UUID.randomUUID();
                Set<Tag> tags = Set.of(Tag.of("account", account), Tag.of("ledger", "hunt"));
                List<TaggedEventMessage<?>> batch = List.of(
                        new GenericTaggedEventMessage<>(event("Deposited"), tags),
                        new GenericTaggedEventMessage<>(event("Withdrawn"), tags));
                Object marker = engine.appendEvents(AppendCondition.none(), null, batch)
                                      .orTimeout(60, TimeUnit.SECONDS)
                                      .join()
                                      .commit()
                                      .orTimeout(60, TimeUnit.SECONDS)
                                      .join();
                System.out.println("AXON SERVER append committed, marker=" + String.valueOf(marker).replace("\n", " "));

                List<String> sourced = engine.source(
                                SourcingCondition.conditionFor(EventCriteria.havingTags(Tag.of("account", account))),
                                null)
                        .reduce(new java.util.ArrayList<String>(), (names, entry) -> {
                            if (entry.getResource(ConsistencyMarker.RESOURCE_KEY) == null) {
                                names.add(entry.message().type().name());
                            }
                            return names;
                        })
                        .orTimeout(60, TimeUnit.SECONDS)
                        .join();
                System.out.println("AXON SERVER sourced by tag " + sourced);

                // then both events come back, which is the round trip through the boundary protocol: two tags per event,
                // and a sourcing condition that selects on one of them. Neither is expressible on the aggregate-based
                // store this suite's other persistent backend uses.
                assertThat(sourced)
                        .as("the events appended under the account tag come back when sourced by it")
                        .containsExactly("Deposited", "Withdrawn");

                // and the backend's own scan agrees, which is the answer every delivery oracle is judged against. It has
                // to be asked asynchronously: the generic next()-loop scan stops at the first empty answer and a gRPC
                // stream is empty until its first message arrives, so that scan reports zero on this store however much
                // it holds -- which would make quiescence trivially true and every loss oracle hold vacuously.
                assertThat(BACKEND.readableEventIds(engine))
                        .as("the backend's asynchronous scan sees what the store holds")
                        .hasSize(2);
            } finally {
                BACKEND.release(engine);
            }

            // and a fresh run starts on an empty store, which is this backend's whole isolation mechanism: the standalone
            // edition refuses a context per run with "[AXONIQ-1700] Maximum number of replication groups reached", so the
            // shared context is emptied instead. Two runs of this backend therefore must not overlap.
            EventStorageEngine next = BACKEND.createEngine();
            try {
                assertThat(BACKEND.readableEventIds(next))
                        .as("a run starts on a purged context")
                        .isEmpty();
            } finally {
                BACKEND.release(next);
            }
        }
    }

    @Nested
    class TheShippedCorpusOnAxonServer {

        @Test
        void inheritsAScenarioByRegistrationAloneAndJudgesTheAppendProtocolOnIt() {
            // given a shipped scenario, edited in no way except to name another store
            Scenario arm = HuntScenarios.appendRejectedAfterMarker()
                                        .onBackend(AxonServerHuntBackend.NAME)
                                        .withTimescale(BackendDifferentialTest.MATRIX_TIMINGS)
                                        .withFaults(org.axonframework.hunt.fault.FaultSchedule.none(
                                                BackendDifferentialTest.MATRIX_SETTLE))
                                        .withBudget(Tier.SMOKE, BackendDifferentialTest.MATRIX_BUDGET);

            // when it is run
            ScenarioResult result = ScenarioRunner.run(arm, Tier.SMOKE, arm.seed(),
                                                       ScenarioRunner.historyDirectory(
                                                               Path.of("target", "hunt-histories", "axonserver-arm")));
            System.out.println("AXONSERVER ARM " + result.verdict() + " " + AxonServerHuntBackend.label());
            result.violations().forEach(violation -> System.out.println("  violation: " + violation));
            result.notes().forEach(note -> System.out.println("  note: " + note));
            result.measurements().forEach(measured -> System.out.println("  measured: " + measured));
            result.notApplicable().forEach(statement -> System.out.println("  n/a: " + statement));

            // then the run happened and reached a verdict
            assertThat(result.verdict()).isNotNull();

            // and this is the coverage this arm adds that nothing else in the suite had: the reference model DECIDES the
            // append protocol against a store whose events outlive the process. Every other persistent store here is the
            // aggregate-based JPA engine, whose append condition is not a boundary over tags and a marker, so the model
            // oracle reports itself inexpressible on it and the protocol goes unjudged wherever persistence is real.
            assertThat(result.notApplicable())
                    .as("a boundary-native persistent store must let the reference model judge the protocol: %s", result)
                    .noneMatch(statement -> statement.contains(ModelConformanceChecker.APPEND_CONFORMS_TO_DCB_MODEL));

            // and what the arm cannot express says so rather than passing quietly. The connector carries no token store,
            // so segment ownership is the framework's in-heap store here, which grants every claim -- exactly as on the
            // in-heap backend. A verdict from this arm is a verdict about the event store.
            assertThat(BACKEND.arbitratesTokenClaims())
                    .as("the connector carries no token store, so this arm arbitrates no claims")
                    .isFalse();

            // and the version combination the run's meaning depends on travelled with the history, so a finding quoting
            // this arm cannot omit the skew it was observed under
            Map<String, String> versions = HistoryView.read(result.history()).header().versions();
            System.out.println("AXONSERVER ARM versions " + versions);
            assertThat(versions)
                    .as("the history must record which combination produced the verdict")
                    .containsKeys("framework", "connector", "image", "engine.shimmed");
            assertThat(versions.get("connector")).contains(AxonServerHuntBackend.CONNECTOR_VERSION);
            assertThat(versions.get("engine.shimmed")).isEqualTo(AxonServerHuntBackend.SHIMMED_METHOD);
        }
    }

    @Nested
    class TheOwnershipOracleOnThisArm {

        @Test
        void reportsItselfInexpressibleRatherThanPassingOnAStoreWithNoOwner() {
            // given the shipped bootstrap scenario, whose claim is entirely about who owns a segment
            Scenario arm = HuntScenarios.concurrentBootstrap()
                                        .onBackend(AxonServerHuntBackend.NAME)
                                        .withTimescale(BackendDifferentialTest.MATRIX_TIMINGS)
                                        .withBudget(Tier.SMOKE, BackendDifferentialTest.MATRIX_BUDGET);

            // when it is run against a store whose token side is the framework's in-heap one
            ScenarioResult result = ScenarioRunner.run(arm, Tier.SMOKE, arm.seed(),
                                                       ScenarioRunner.historyDirectory(
                                                               Path.of("target", "hunt-histories",
                                                                       "axonserver-bootstrap")));
            System.out.println("AXONSERVER BOOTSTRAP " + result.verdict());
            result.notApplicable().forEach(statement -> System.out.println("  n/a: " + statement));

            // then the claim invariant says it cannot be judged here. The connector carries no token store, so four
            // nodes each believe they own every segment; an ownership assertion made against that holds without checking
            // anything, and recording it as a pass would claim coverage this arm does not have.
            assertThat(result.notApplicable())
                    .as("a store that arbitrates no claims must say so: %s", result)
                    .anySatisfy(statement -> assertThat(statement)
                            .contains(OwnershipChecker.AT_MOST_ONE_SEGMENT_OWNER, "not expressible"));
        }
    }
}
