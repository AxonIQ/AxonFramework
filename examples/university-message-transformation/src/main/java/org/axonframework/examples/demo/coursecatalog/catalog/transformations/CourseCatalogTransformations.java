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

/**
 * Composes the catalog's event transformations into a single {@link EventTransformerChain}.
 * <p>
 * The chain matches each event by identity and re-applies transformations until none matches, so
 * multi-step version hops compose on their own. A stored v1 {@code CoursePublished} is lifted to v2 and
 * then to v3, and a {@code CourseOffered} is first renamed to {@code CoursePublished} before flowing
 * through those same version hops. Each step matches a different identity, so registration order does
 * not affect the outcome here; the transformations are grouped only for readability.
 * <p>
 * When several transformations could match the same event, the most specific one wins regardless of
 * registration order: an exact {@code from} always beats a predicate, and two transformations claiming
 * the same exact identity are rejected when the chain is built. This chain has a single predicate
 * ({@link WelcomeMessageBetaCleanup}), so no such contest arises.
 * <p>
 * {@code WelcomeMessageBetaCleanup} folds the open-ended range of {@code 0.x} beta versions up to
 * {@code 1.0.0} with a predicate, the right tool when the versions to fold cannot be enumerated up
 * front. An event that evolves through known versions is instead modelled one version step at a time,
 * as {@code CoursePublished} and {@code StudentRegistered} are above.
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
                                    .register(SystemHeartbeatDrop.build())
                                    .build();
    }
}
