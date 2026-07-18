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

package org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview;

import java.util.List;

/**
 * Whole-catalog snapshot returned by the
 * {@link org.axonframework.examples.demo.coursecatalog.catalog.read.catalogview.GetCourseCatalogView}
 * query.
 *
 * @param courses             every published course, in registration order
 * @param enrolments          every student-in-course enrolment, in arrival order
 * @param announcements       every system announcement, in arrival order
 * @param registeredStudents  total number of students registered in the catalog
 * @param welcomeMessages     every welcome message, lifted to the current shape by the chain
 * @param heartbeatsSeen      number of {@code SystemHeartbeat} pings the projection recorded; stays
 *                            zero while the heartbeat drop is registered, even though pings are stored
 */
public record CourseCatalogView(
        List<CatalogViewReadModel> courses,
        List<EnrolmentReadModel> enrolments,
        List<String> announcements,
        int registeredStudents,
        List<WelcomeMessageView> welcomeMessages,
        int heartbeatsSeen
) {
}
