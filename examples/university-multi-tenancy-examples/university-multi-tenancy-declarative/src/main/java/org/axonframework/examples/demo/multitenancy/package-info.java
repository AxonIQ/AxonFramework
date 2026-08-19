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
 * The declarative multi-tenancy demo: the runnable application, its declarative Configuration API
 * wiring, and the {@code demo.axon-server.enabled} toggle. The tenant lifecycle it runs and the driving
 * utilities it uses live in the shared module, so this module only adds its declarative configuration.
 */
@NullMarked
package org.axonframework.examples.demo.multitenancy;

import org.jspecify.annotations.NullMarked;
