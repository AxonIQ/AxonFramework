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

package org.axonframework.examples.demo.coursecatalog.catalog.automation.overbookingnotifier;

import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;
import org.axonframework.examples.demo.coursecatalog.shared.notifier.NotificationService;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.util.Objects;

/**
 * Flags a {@link CoursePublished} whose capacity range is wider than
 * {@value #LOOSE_COMMITMENT_THRESHOLD_SEATS} seats between min and max. Such a loose
 * commitment can leave students over-promised or faculty under-resourced; the notification
 * gives operations a chance to confirm the cohort size before enrolment opens.
 * <p>
 * Receives the current-shape {@link CoursePublished} even when the stored event was an
 * older shape: the transformation chain lifts events before any handler sees them.
 */
final class OverbookingNotifier {

    /** Maximum span (max minus min seats) considered a tight capacity commitment. */
    static final int LOOSE_COMMITMENT_THRESHOLD_SEATS = 20;

    static final String OVERBOOKING_TOPIC = "overbooking-risk";

    private OverbookingNotifier() {
    }

    static MessageStream.Empty<Message> react(EventMessage event, ProcessingContext context) {
        MessageConverter converter = context.component(MessageConverter.class);
        CoursePublished published = Objects.requireNonNull(
                event.payloadAs(CoursePublished.class, converter),
                "CoursePublished payload must not be null");
        int min = published.range().min();
        int max = published.range().max();
        int span = max - min;
        if (span > LOOSE_COMMITMENT_THRESHOLD_SEATS) {
            NotificationService notifier = context.component(NotificationService.class);
            notifier.send(new NotificationService.Notification(
                    OVERBOOKING_TOPIC,
                    "Loose capacity commitment for course '" + published.name() + "': published with range "
                            + min + "-" + max + " (" + span + " seats apart, threshold "
                            + LOOSE_COMMITMENT_THRESHOLD_SEATS + "). "
                            + "Operations should confirm the intended cohort size before enrolment opens."
            ));
        }
        return MessageStream.empty();
    }
}
