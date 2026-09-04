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

package org.axonframework.modelling.saga;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.axonframework.messaging.core.ScopeDescriptor;

import java.beans.ConstructorProperties;
import java.util.Objects;

/**
 * Describes the scope of a Saga by means of its type and identifier.
 *
 * @author Steven van Beelen
 * @since 3.3
 */
public class SagaScopeDescriptor implements ScopeDescriptor {

    private final String type;
    private final Object identifier;

    /**
     * Instantiate a SagaScopeDescriptor with the provided {@code type} and {@code identifier}.
     *
     * @param type       A {@link String} describing the type of the Saga
     * @param identifier An {@link Object} denoting the identifier of the Saga
     */
    @JsonCreator
    @ConstructorProperties({ "type", "identifier" })
    public SagaScopeDescriptor(@JsonProperty("type") String type, @JsonProperty("identifier") Object identifier) {
        this.type = type;
        this.identifier = identifier;
    }

    /**
     * The type of Saga described by this scope
     *
     * @return the type of Saga described by this scope
     */
    public String getType() {
        return type;
    }

    /**
     * The identifier of the Saga targeted with this scope
     *
     * @return identifier of the Saga targeted with this scope
     */
    public Object getIdentifier() {
        return identifier;
    }

    @Override
    public String scopeDescription() {
        return String.format("SagaScopeDescriptor for type [%s] and identifier [%s]", type, identifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, identifier);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final SagaScopeDescriptor other = (SagaScopeDescriptor) obj;
        return Objects.equals(this.type, other.type)
                && Objects.equals(this.identifier, other.identifier);
    }

    @Override
    public String toString() {
        return "SagaScopeDescriptor{" +
                "type='" + type + '\'' +
                ", identifier=" + identifier +
                '}';
    }
}
