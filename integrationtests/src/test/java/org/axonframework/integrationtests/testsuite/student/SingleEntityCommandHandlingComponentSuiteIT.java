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

package org.axonframework.integrationtests.testsuite.student;

/**
 * Runs {@link SingleEntityCommandHandlingComponentIT} against the store this run selected.
 * <p>
 * The class carries no infrastructure of its own. The store comes from
 * {@link org.axonframework.integrationtests.testsuite.infrastructure.TestInfrastructures#selected()}, which reads the
 * {@code hunt.backend} system property and defaults to the in-memory components, so this one class produces a verdict
 * for every backend by being run once per backend instead of being copied per backend.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class SingleEntityCommandHandlingComponentSuiteIT extends SingleEntityCommandHandlingComponentIT {

}
