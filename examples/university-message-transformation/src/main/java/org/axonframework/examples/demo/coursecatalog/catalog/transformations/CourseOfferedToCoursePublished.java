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

package org.axonframework.examples.demo.coursecatalog.catalog.transformations;

import io.axoniq.framework.messaging.transformation.events.EventTransformation;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.messaging.core.MessageType;

/**
 * Renames the catalog's earliest event, {@code CourseOffered} {@code 1.0.0}, to the name the
 * domain settled on, {@code CoursePublished} {@code 1.0.0}.
 * <p>
 * A pure rename: only the identity changes, the payload is carried over unchanged, so there is no
 * mapper. The renamed {@code CoursePublished} {@code 1.0.0} then flows through
 * {@link CoursePublishedV1ToV2} and {@link CoursePublishedV2ToV3}, so a historic {@code CourseOffered}
 * reaches handlers as the current {@code CoursePublished}. Because a
 * {@link EventTransformation.TransformStep#transform transform(...)} may only change the version, a
 * name change like this needs {@link EventTransformation#rename rename(...)}.
 */
public final class CourseOfferedToCoursePublished {

    private static final MessageType FROM = new MessageType(CourseCatalogMessageNames.COURSE_OFFERED, "1.0.0");
    private static final MessageType TO   = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0");

    private CourseOfferedToCoursePublished() {
    }

    /** @return the transformation registered into the chain */
    public static EventTransformation build() {
        return EventTransformation.rename(FROM, TO);
    }
}
