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
 * Spring Boot autoconfiguration for the Axon Framework 4 Sagas carried by {@code axon-legacy}. Activates the saga
 * store and saga processor wiring when the matching infrastructure (a {@code DataSource}, an
 * {@code EntityManagerFactory}, or neither) is available.
 */
@NullMarked
package org.axonframework.extension.springboot.autoconfig;

import org.jspecify.annotations.NullMarked;
