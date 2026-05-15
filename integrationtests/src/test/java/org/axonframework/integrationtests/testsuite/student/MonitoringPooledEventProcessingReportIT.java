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

package org.axonframework.integrationtests.testsuite.student;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.test.util.MessageMonitorReport;
import org.axonframework.test.util.RecordingMessageMonitor;
import org.junit.jupiter.api.*;


import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * This tests uses a {@link RecordingMessageMonitor} to verify that successfully processed events and ignored events are
 * reported correctly.
 */
public abstract class MonitoringPooledEventProcessingReportIT extends AbstractStudentIT {

    private static final String NAME = MonitoringPooledEventProcessingReportIT.class.getSimpleName();

    protected MessageMonitorReport reportedMessages = new MessageMonitorReport();

    record UnknownEvent(String id) {
        // not configured in test setup, will be ignored
    }

    record KnownEvent(String id) {
        // configured in test setup, will be reported as success or failure
    }

    private final String id = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        reportedMessages.clear();
    }

    @Test
    void reportsIgnoredForUnknownEvent() {
        startApp();

        storeEvent(UnknownEvent.class, new UnknownEvent(id), Metadata.with("id", id));

        await().untilAsserted(
                () -> assertThat(reportedMessages.ignoredReports().stream()
                                                 .filter(it -> it.message().metadata().get("id").equals(id))
                                                 .findFirst())
                        .as("UnknownEvent(%s) should have been reported as ignored, but wasn't.", id)
                        .isNotEmpty()
        );
    }

    @Test
    void reportsFailureForKnownEventWithError() {
        startApp();

        storeEvent(KnownEvent.class, new KnownEvent(id), Metadata.with("id", id).and("type", "ERROR"));

        await().untilAsserted(() -> {
            assertThat(reportedMessages.failureReports().stream()
                                       .filter(it -> it.message().metadata().get("id").equals(id))
                                       .findFirst())
                    .as("Error on KnownEvent(%s) should have been reported as failure, but wasn't.", id)
                    .isNotEmpty();
        });
    }

    @Test
    void reportsSuccessForKnownEvent() {
        startApp();

        storeEvent(KnownEvent.class, new KnownEvent(id), Metadata.with("id", id));

        await().untilAsserted(
                () -> assertThat(reportedMessages.successReports().stream()
                                                 .filter(it -> it.message().metadata().get("id").equals(id))
                                                 .findFirst())
                        .as("KnownEvent(%s) should have been reported as success, but wasn't.", id)
                        .isNotEmpty()
        );
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        // purge events to restart with an empty eventstore and avoid processing historic events
        purgeData();

        // a noop setup that allows verification of ignored event
        configurer.messaging(mc -> mc
                .registerMessageMonitor(c -> new RecordingMessageMonitor(reportedMessages))
                .eventProcessing(ep -> ep.pooledStreaming(ps -> {
                    ps.processor(
                            EventProcessorModule.pooledStreaming(NAME).eventHandlingComponents(
                                                        components -> {
                                                            SimpleEventHandlingComponent handlingComponent = SimpleEventHandlingComponent.create(
                                                                    "test",
                                                                    SequentialPolicy.INSTANCE
                                                            );
                                                            handlingComponent.subscribe(
                                                                    new QualifiedName(KnownEvent.class),
                                                                    (event, context) -> {
                                                                        if ("ERROR".equals(event.metadata().get("type"))) {
                                                                            throw new RuntimeException("Failures are expected");
                                                                        }
                                                                        return MessageStream.empty();
                                                                    }
                                                            );
                                                            return components.declarative(
                                                                    "handlingComponent",
                                                                    cfg -> handlingComponent
                                                            );
                                                        }
                                                )
                                                .customized((cfg, customization) -> customization.eventCriteria(
                                                        (supportedTyped) -> EventCriteria.havingAnyTag()
                                                ))
                    );
                    return ps;
                }))
        );
        return super.testSuiteConfigurer(configurer);
    }
}
