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

package org.axonframework.test.saga;

/**
 * Interface to an object that publishes events on behalf of an aggregate. The sequence number on the events must be
 * exactly sequential per aggregate.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public interface GivenAggregateEventPublisher {

    /**
     * Register the given {@code events} as being published somewhere in the past. These events are used to prepare the
     * state of Sagas listening to them. Any commands or events sent out by the saga as reaction to these events is
     * ignored.
     *
     * @param events The events published by the aggregate
     * @return a reference to the fixture to support a fluent interface
     */
    ContinuedGivenState published(Object... events);
}
