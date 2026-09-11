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

package org.axonframework.modelling.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.axonframework.messaging.core.ScopeDescriptor;

import java.beans.ConstructorProperties;
import java.util.Objects;
import java.util.function.Supplier;

import static org.axonframework.common.Assert.notNull;

/**
 * Describes the scope of an Aggregate by means of its type and identifier.
 * <p>
 * This is the aggregate counterpart of {@link org.axonframework.modelling.saga.SagaScopeDescriptor}. A
 * {@link org.axonframework.deadline.DeadlineManager} stores the descriptor a deadline was scheduled against, so on
 * expiry it can tell an aggregate-scoped deadline from a saga-scoped one and resolve the right target. The two are
 * distinct types and never compare equal, even when their {@code type} and {@code identifier} coincide.
 * <p>
 * Note that this descriptor only identifies an aggregate as the target of a scoped message. The API for declaring
 * aggregates and invoking their handlers is not part of this module.
 *
 * @author Steven van Beelen
 * @since 3.3
 */
public class AggregateScopeDescriptor implements ScopeDescriptor {

    private final String type;
    private Object identifier;
    private transient Supplier<Object> identifierSupplier;

    /**
     * Instantiate an AggregateScopeDescriptor with a {@code type} and {@code identifierSupplier}. Using the {@code
     * identifierSupplier} instead of an {@link Object} for the {@code identifier} allows the creating processes to
     * provide the identifier lazily.
     * This is necessary when Aggregate's identifier is not create yet, for example when the AggregateScopeDescriptor is
     * created whilst the Aggregate is still under construction.
     *
     * @param type               A {@link String} describing the type of the Aggregate
     * @param identifierSupplier A {@link Supplier} of {@link Object}, which can supply the identifier of the Aggregate
     */
    public AggregateScopeDescriptor(String type, Supplier<Object> identifierSupplier) {
        notNull(
                identifierSupplier,
                () -> "A Supplier for the identifier field is required when using this constructor"
        );

        this.type = type;
        this.identifierSupplier = identifierSupplier;
    }

    /**
     * Instantiate an AggregateScopeDescriptor with the provided {@code type} and {@code identifier}.
     *
     * @param type       A {@link String} describing the type of the Aggregate
     * @param identifier An {@link Object} denoting the identifier of the Aggregate
     */
    @JsonCreator
    @ConstructorProperties({ "type", "identifier" })
    public AggregateScopeDescriptor(@JsonProperty("type") String type, @JsonProperty("identifier") Object identifier) {
        this.type = type;
        this.identifier = identifier;
    }

    /**
     * Returns the type of Aggregate, as String, targeted by this scope
     *
     * @return the Aggregate targeted by this scope
     */
    public String getType() {
        return type;
    }

    /**
     * The identifier of the Aggregate targeted with this scope
     *
     * @return the identifier of the target Aggregate
     */
    public Object getIdentifier() {
        if (identifier == null) {
            identifier = identifierSupplier.get();
        }
        return identifier;
    }

    @Override
    public String scopeDescription() {
        return String.format("AggregateScopeDescriptor for type [%s] and identifier [%s]", type, getIdentifier());
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, getIdentifier());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final AggregateScopeDescriptor other = (AggregateScopeDescriptor) obj;
        return Objects.equals(this.type, other.type)
                && Objects.equals(this.getIdentifier(), other.getIdentifier());
    }

    @Override
    public String toString() {
        return "AggregateScopeDescriptor{" +
                "type=" + type +
                ", identifier='" + getIdentifier() + '\'' +
                '}';
    }
}
