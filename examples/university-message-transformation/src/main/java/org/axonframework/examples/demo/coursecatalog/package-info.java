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

/**
 * Root package of the University Message Transformation demo. Demonstrates the AxonIQ
 * Framework event message transformation chain on a course-catalog domain that
 * has evolved across three years of schema changes. The application code is built
 * against the latest event shape, historic events on disk are lifted to the latest
 * shape at read time by a chain of transformations.
 */
@NullMarked
package org.axonframework.examples.demo.coursecatalog;

import org.jspecify.annotations.NullMarked;
