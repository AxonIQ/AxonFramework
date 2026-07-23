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
 * The university's tenant-aware components, the subject of this demo. One instance of each exists per
 * tenant, and the framework injects the right tenant's instance into a handler that declares the
 * component's type as a parameter. There are two, to show that several tenant-scoped types coexist and
 * are each matched by their own type: a course-statistics store and an audit log.
 */
@NullMarked
package org.axonframework.examples.demo.multitenancy.university.component;

import org.jspecify.annotations.NullMarked;
