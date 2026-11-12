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

package org.axonframework.extension.spring.messaging.unitofwork;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A minimal entity for tests that need to write through an entity manager.
 *
 * @author John Hendrikx
 */
@Entity
@Table(name = "connection_test_entity")
public class ConnectionTestEntity {

    @Id
    private Long id;

    private String name;

    protected ConnectionTestEntity() {
    }

    ConnectionTestEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}
