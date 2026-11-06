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

package org.axonframework.common.configuration;

/**
 * Interface describing an enhancement of the {@link ComponentRegistry} from the Axon Framework configuration API,
 * taking effect during {@link ApplicationConfigurer#build() build} of the configurer.
 * <p>
 * Through implementing the {@link #enhance(ComponentRegistry)} operation a {@code ConfigurationEnhancer} is able to,
 * for example, {@link ComponentRegistry#registerComponent(Class, ComponentBuilder) register} components and
 * {@link ComponentRegistry#registerDecorator(Class, int, ComponentDecorator) register} decorators. The registration of
 * components and/or decorators can be made conditional by using the {@link ComponentRegistry#hasComponent(Class)}
 * operation.
 * <p>
 * If the components and/or decorators the enhancers includes have start-up or shutdown handlers, consider using the
 * {@link ComponentRegistry#registerComponent(ComponentDefinition) component definition} and
 * {@link ComponentRegistry#registerDecorator(DecoratorDefinition) decorator definition} registration methods. Both
 * these operations inspect a definition to be given, for which the builder flow includes defining start-up and shutdown
 * handlers.
 * <p>
 * Note that enhancers have an {@link #order()} in which they enhance the {@code Configurer}. When not otherwise
 * specified, the order defaults to {@code 0}. Thus, without specifying the order, the insert order of enhancer dictates
 * the order.
 *
 * @author Steven van Beelen
 * @since 3.2.0
 */
@FunctionalInterface
public interface ConfigurationEnhancer {

    /**
     * Enhances the given {@code registry} with, for example, additional {@link Component components} and
     * {@link ComponentDecorator decorators}.
     *
     * @param registry The registry instance to enhance.
     */
    void enhance(ComponentRegistry registry);

    /**
     * Returns the relative order this enhancer should be invoked in, compared to other instances.
     * <p>
     * Use lower (negative) values for enhancers providing sensible defaults, and higher values for enhancers that
     * should be able to override values potentially previously set. Defaults to {@code 0} when not set.
     *
     * @return The order in which this enhancer should be invoked.
     */
    /**
     * Returns the name of this enhancer.
     * <p>
     * Defaults to the fully qualified class name of this instance.
     *
     * @return The name of this enhancer.
     */
    default String name() {
        return getClass().getName();
    }

    default int order() {
        return 0;
    }
}
