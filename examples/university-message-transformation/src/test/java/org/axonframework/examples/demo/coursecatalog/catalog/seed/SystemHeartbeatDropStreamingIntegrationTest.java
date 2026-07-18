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

package org.axonframework.examples.demo.coursecatalog.catalog.seed;

import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogAxonTestFixture;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.Ids;
import org.axonframework.examples.demo.coursecatalog.catalog.events.SystemHeartbeat;
import org.axonframework.examples.demo.coursecatalog.shared.ids.CourseId;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the drop fires on the streaming read path: a stored {@code SystemHeartbeat}
 * is removed by the {@code SystemHeartbeatDrop} transformation, and surviving events keep their original
 * tracking token, so a reader resuming from a survivor's token after the drop is not re-delivered the
 * heartbeat (it never reaches a handler). This test re-opens the store from a token rather than
 * restarting a real processor.
 * <p>
 * Unlike the catalog projection (which type-filters to the events it handles and so never reads a
 * heartbeat), this test opens the configured {@link EventStore} directly with no type filter, so the
 * heartbeat really is read from storage and the chain must drop it on the way out.
 */
class SystemHeartbeatDropStreamingIntegrationTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void beforeEach() {
        fixture = CourseCatalogAxonTestFixture.app();
    }

    @AfterEach
    void afterEach() {
        fixture.stop();
    }

    @Test
    void droppedHeartbeatIsAbsentFromTheStreamAndARestartReprocessesNothing() {
        fixture.given()
               .events(new LegacyEventSeeder.CoursePublishedV1(
                               Ids.CATALOG_ID, CourseId.of("streaming-1"), "Course One", 10),
                       new SystemHeartbeat(Ids.CATALOG_ID, 1),
                       new LegacyEventSeeder.CoursePublishedV1(
                               Ids.CATALOG_ID, CourseId.of("streaming-2"), "Course Two", 20))
               .then()
               .await(r -> r.expect(cfg -> {
                   EventStore store = cfg.getComponent(EventStore.class);
                   TrackingToken head = store.firstToken(null).join();

                   // Reading the whole stream from its head with no type filter: the heartbeat is
                   // dropped, while both courses surface (lifted to their current shape).
                   List<MessageStream.Entry<EventMessage>> all =
                           drain(store, StreamingCondition.startingFrom(head));
                   assertThat(all).as("dropped heartbeat must not reach the stream")
                                  .noneMatch(SystemHeartbeatDropStreamingIntegrationTest::isHeartbeat);
                   assertThat(all).filteredOn(SystemHeartbeatDropStreamingIntegrationTest::isCoursePublished)
                                  .hasSize(2);

                   // Resuming from the first course's token skips the dropped heartbeat that sits next
                   // in storage: the heartbeat is never re-delivered, while the later course still
                   // arrives. So a reader resuming after the drop does not reprocess it.
                   TrackingToken afterFirstCourse = TrackingToken.fromContext(all.getFirst()).orElseThrow();
                   List<MessageStream.Entry<EventMessage>> afterRestart =
                           drain(store, StreamingCondition.startingFrom(afterFirstCourse));
                   assertThat(afterRestart).noneMatch(SystemHeartbeatDropStreamingIntegrationTest::isHeartbeat);
                   assertThat(afterRestart).anyMatch(SystemHeartbeatDropStreamingIntegrationTest::isCoursePublished);
               }));
    }

    /**
     * Drains every entry currently available from a streaming read, then closes the stream. A
     * streaming read does not complete (it tails for new events), so the entries are pulled with
     * {@link MessageStream#next()} until none remain rather than reduced to completion.
     */
    private static List<MessageStream.Entry<EventMessage>> drain(EventStore store, StreamingCondition condition) {
        MessageStream<EventMessage> stream = store.open(condition, null);
        try {
            List<MessageStream.Entry<EventMessage>> entries = new ArrayList<>();
            Optional<MessageStream.Entry<EventMessage>> next;
            while ((next = stream.next()).isPresent()) {
                entries.add(next.get());
            }
            return entries;
        } finally {
            stream.close();
        }
    }

    private static boolean isHeartbeat(MessageStream.Entry<EventMessage> entry) {
        return entry.message().type().qualifiedName().name().equals(CourseCatalogMessageNames.SYSTEM_HEARTBEAT);
    }

    private static boolean isCoursePublished(MessageStream.Entry<EventMessage> entry) {
        return entry.message().type().qualifiedName().name().equals(CourseCatalogMessageNames.COURSE_PUBLISHED);
    }
}
