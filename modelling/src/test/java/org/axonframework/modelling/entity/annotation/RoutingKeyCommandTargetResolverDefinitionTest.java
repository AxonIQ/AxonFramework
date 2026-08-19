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

package org.axonframework.modelling.entity.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.entity.child.CommandTargetResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link RoutingKeyCommandTargetResolverDefinition}.
 *
 * @author Mitchell Herrijgers
 */
@ExtendWith(MockitoExtension.class)
class RoutingKeyCommandTargetResolverDefinitionTest {

    private final CommandTargetResolverDefinition definition = new RoutingKeyCommandTargetResolverDefinition();

    @Mock
    private AnnotatedEntityMetamodel<ChildEntity> childEntityMetamodel;

    @Test
    void allowsNoRoutingKeyOnSingleValueEntityMember() throws NoSuchFieldException {
        CommandTargetResolver<ChildEntity> result = definition.createCommandTargetResolver(
                childEntityMetamodel,
                SingleChildEntity.class.getDeclaredField("child")
        );

        assertThat(result).isEqualTo(CommandTargetResolver.MATCH_ANY());
    }

    @Test
    void doesNotAllowMissingRoutingKeyOnCollectionTypeMember() {
        assertThatThrownBy(
                () -> definition.createCommandTargetResolver(
                        childEntityMetamodel,
                        ListChildEntityWithoutRoutingKey.class.getDeclaredField("child")
                ),
                "Expected AxonConfigurationException when no routing key is present on a collection type member"
        ).isInstanceOf(AxonConfigurationException.class);
    }

    @Test
    void returnsMatcherForListChildEntityWithRoutingKey() throws NoSuchFieldException {
        CommandTargetResolver<ChildEntity> result = definition.createCommandTargetResolver(
                childEntityMetamodel,
                ListChildEntityWithRoutingKey.class.getDeclaredField("child")
        );

        assertThat(result).isNotNull()
                          .isNotEqualTo(CommandTargetResolver.MATCH_ANY());
    }

    @Test
    void doesNotAllowRoutingKeyMismatchBetweenMessageAndListEntity() throws NoSuchFieldException {
        CommandTargetResolver<ChildEntity> result = definition.createCommandTargetResolver(
                childEntityMetamodel,
                ListChildEntityWithNonMatchingRoutingKey.class.getDeclaredField("child")
        );

        MessageType messageType = new MessageType(TestCommand.class);
        //noinspection unchecked,rawtypes
        when(childEntityMetamodel.getExpectedRepresentation(messageType.qualifiedName()))
                .thenReturn((Class) TestCommand.class);

        assertThatThrownBy(() -> result.getTargetChildEntity(
                List.of(new ChildEntity()),
                new GenericCommandMessage(messageType, new TestCommand("someValue")),
                new StubProcessingContext()
        )).isInstanceOf(UnknownRoutingKeyException.class);
    }

    @Test
    void doesNotAllowMissingRoutingKeyOnMapTypeMember() {
        assertThatThrownBy(
                () -> definition.createCommandTargetResolver(
                        childEntityMetamodel,
                        MapChildEntityWithoutRoutingKey.class.getDeclaredField("child")
                ),
                "Expected AxonConfigurationException when no routing key is present on a Map type member"
        ).isInstanceOf(AxonConfigurationException.class);
    }

    @Test
    void returnsMatcherForMapChildEntityWithRoutingKey() throws NoSuchFieldException {
        CommandTargetResolver<ChildEntity> result = definition.createCommandTargetResolver(
                childEntityMetamodel,
                MapChildEntityWithRoutingKey.class.getDeclaredField("child")
        );

        assertThat(result).isNotNull()
                          .isNotEqualTo(CommandTargetResolver.MATCH_ANY());
    }

    @Test
    void doesNotAllowRoutingKeyMismatchBetweenMessageAndMapEntity() throws NoSuchFieldException {
        CommandTargetResolver<ChildEntity> result = definition.createCommandTargetResolver(
                childEntityMetamodel,
                MapChildEntityWithNonMatchingRoutingKey.class.getDeclaredField("child")
        );

        MessageType messageType = new MessageType(TestCommand.class);
        //noinspection unchecked,rawtypes
        when(childEntityMetamodel.getExpectedRepresentation(messageType.qualifiedName()))
                .thenReturn((Class) TestCommand.class);

        assertThatThrownBy(() -> result.getTargetChildEntity(
                List.of(new ChildEntity()),
                new GenericCommandMessage(messageType, new TestCommand("someValue")),
                new StubProcessingContext()
        )).isInstanceOf(UnknownRoutingKeyException.class);
    }

    static class SingleChildEntity {

        @SuppressWarnings("unused")
        @EntityMember
        private ChildEntity child;
    }

    static class ListChildEntityWithoutRoutingKey {

        @SuppressWarnings("unused")
        @EntityMember
        private List<ChildEntity> child;
    }

    static class ListChildEntityWithRoutingKey {

        @SuppressWarnings("unused")
        @EntityMember(routingKey = "key")
        private List<ChildEntity> child;
    }

    static class ListChildEntityWithNonMatchingRoutingKey {

        @SuppressWarnings("unused")
        @EntityMember(routingKey = "incorrect")
        private List<ChildEntity> child;
    }

    static class MapChildEntityWithoutRoutingKey {

        @SuppressWarnings("unused")
        @EntityMember
        private Map<String, ChildEntity> child;
    }

    static class MapChildEntityWithRoutingKey {

        @SuppressWarnings("unused")
        @EntityMember(routingKey = "key")
        private Map<String, ChildEntity> child;
    }

    static class MapChildEntityWithNonMatchingRoutingKey {

        @SuppressWarnings("unused")
        @EntityMember(routingKey = "incorrect")
        private Map<String, ChildEntity> child;
    }

    static class ChildEntity {

        @SuppressWarnings("unused")
        String key() {
            return "key";
        }
    }

    private record TestCommand(String key) {

    }
}
