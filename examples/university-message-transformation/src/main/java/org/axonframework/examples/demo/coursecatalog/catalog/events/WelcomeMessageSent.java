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

package org.axonframework.examples.demo.coursecatalog.catalog.events;

import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogTags;
import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

/**
 * A welcome message sent to a newly-registered student. Lifted by the chain from
 * the historic beta-versioned shapes (0.5 / 0.7 / 0.9), with the {@code subject}
 * field dropped during the beta cleanup.
 *
 * @param studentId the recipient
 * @param body      the message body
 */
@Event(namespace = CourseCatalogMessageNames.NAMESPACE, name = "WelcomeMessageSent", version = "1.0.0")
public record WelcomeMessageSent(
        @EventTag(key = CourseCatalogTags.STUDENT_ID) StudentId studentId,
        String body
) {
}
