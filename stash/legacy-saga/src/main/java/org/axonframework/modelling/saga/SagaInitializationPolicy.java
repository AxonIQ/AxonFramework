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

/**
 * Describes the conditions under which a Saga should be created, and which AssociationValue it should be initialized
 * with.
 *
 * @author Allard Buijze
 * @since 2.1
 */
public class SagaInitializationPolicy {

    /**
     * Value indicating there is no Initialization required
     */
    public static final SagaInitializationPolicy NONE = new SagaInitializationPolicy(SagaCreationPolicy.NONE, null);

    private final SagaCreationPolicy creationPolicy;
    private final AssociationValue initialAssociationValue;

    /**
     * Creates an instance using the given {@code creationPolicy} and {@code initialAssociationValue}. To
     * indicate that no saga should be created, use {@link #NONE} instead of this constructor.
     *
     * @param creationPolicy          The policy describing the condition to create a new instance
     * @param initialAssociationValue The association value a new Saga instance should be given
     */
    public SagaInitializationPolicy(SagaCreationPolicy creationPolicy, AssociationValue initialAssociationValue) {
        this.creationPolicy = creationPolicy;
        this.initialAssociationValue = initialAssociationValue;
    }

    /**
     * Returns the creation policy
     *
     * @return the creation policy
     */
    public SagaCreationPolicy getCreationPolicy() {
        return creationPolicy;
    }

    /**
     * Returns the initial association value for a newly created saga. May be {@code null}.
     *
     * @return the initial association value for a newly created saga
     */
    public AssociationValue getInitialAssociationValue() {
        return initialAssociationValue;
    }
}
