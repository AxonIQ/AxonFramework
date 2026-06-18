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

import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.examples.demo.coursecatalog.catalog.events.CoursePublished;

/**
 * Composes the catalog's event transformations into a single
 * {@link EventTransformerChain}. Registration order matters: v1 to v2 must precede
 * v2 to v3 so a stored v1 event reaches a handler as the current shape. The
 * {@code CourseOffered} rename runs first, so a renamed event then flows through the
 * {@link CoursePublished} version chain.
 */
public final class CourseCatalogTransformations {

    private CourseCatalogTransformations() {
    }

    /** @return the chain registered with the catalog's configuration */
    public static EventTransformerChain chain() {
        return EventTransformerChain.builder()
                                    .register(CourseOfferedToCoursePublished.build())
                                    .register(SystemAnnouncementLegacyUplift.build())
                                    .register(CoursePublishedV1ToV2.build())
                                    .register(CoursePublishedV2ToV3.build())
                                    .register(StudentRegisteredV1ToV2.build())
                                    .register(StudentRegisteredV2ToV3.build())
                                    .register(WelcomeMessageBetaCleanup.build())
                                    .build();
    }
}
