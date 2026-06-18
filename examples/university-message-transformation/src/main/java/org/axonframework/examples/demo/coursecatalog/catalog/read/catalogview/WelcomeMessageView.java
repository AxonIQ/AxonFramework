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

import org.axonframework.examples.demo.coursecatalog.shared.ids.StudentId;

/**
 * One welcome message in the catalog read model. The projection only ever sees the
 * current shape: a historic beta-versioned {@code WelcomeMessageSent} (0.5 / 0.7 / 0.9)
 * is lifted to {@code 1.0.0} by the transformation chain on the read path, with the
 * legacy {@code subject} field dropped, before the projection records it here.
 *
 * @param studentId the recipient
 * @param body      the message body
 */
public record WelcomeMessageView(StudentId studentId, String body) {
}
