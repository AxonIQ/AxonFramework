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
 * Registration of Sagas with an Axon Framework 5 configuration.
 * <p>
 * An {@link org.axonframework.modelling.saga.AnnotatedSagaManager} is an
 * {@link org.axonframework.messaging.eventhandling.EventHandlingComponent}, so a Saga is registered on an
 * {@link org.axonframework.messaging.eventhandling.configuration.EventProcessorModule EventProcessorModule} like any
 * other component, and inherits everything the processor offers. This package holds the assembly of the manager and
 * its repository, which is the only part a user would otherwise write out by hand.
 */
@NullMarked
package org.axonframework.modelling.saga.configuration;

import org.jspecify.annotations.NullMarked;
