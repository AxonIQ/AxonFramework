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
 * A JPA-backed {@link org.axonframework.modelling.saga.repository.SagaStore}, storing sagas as
 * {@link org.axonframework.modelling.saga.repository.jpa.SagaEntry} rows and their associations as
 * {@link org.axonframework.modelling.saga.repository.jpa.AssociationValueEntry} rows.
 * <p>
 * The entity and column layout is that of Axon Framework 4, so an existing saga table can be read and updated without
 * migration, provided its saga type column holds saga class names. See
 * {@link org.axonframework.modelling.saga.repository.jpa.JpaSagaStore} for when it might not.
 * <p>
 * These types carry the Axon Framework 4 API, to ease migration of projects that cannot move off it in one go. The
 * departures are those Axon Framework 5 forced by removing the {@code Serializer}:
 * {@code JpaSagaStore.Builder#serializer} became {@code converter}, the
 * {@link org.axonframework.modelling.saga.repository.jpa.SagaEntry} constructor takes a
 * {@link org.axonframework.conversion.Converter}, and {@code JpaSagaStore#serializedObjectType} is gone along with the
 * {@code SerializedSaga} type it selected.
 */
@NullMarked
package org.axonframework.modelling.saga.repository.jpa;

import org.jspecify.annotations.NullMarked;
