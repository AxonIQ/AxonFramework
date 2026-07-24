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
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the {@link MapEntityChildModelDefinition} directly (type support and child type resolution), and end-to-end
 * through the {@link MapDefinitionTestTeam} domain model to validate that a {@code Map}-typed {@link EntityMember} is
 * wired up correctly by {@link AnnotatedEntityMetamodel}: command and event routing by routing key, and no ambiguity
 * with {@link SingleEntityChildModelDefinition}.
 *
 * @author Steven van Beelen
 */
class MapEntityChildModelDefinitionTest
        extends AbstractAnnotatedEntityMetamodelTest<MapEntityChildModelDefinitionTest.MapDefinitionTestTeam> {

    private final MapEntityChildModelDefinition definition = new MapEntityChildModelDefinition();

    @Override
    protected AnnotatedEntityMetamodel<MapDefinitionTestTeam> getMetamodel() {
        return AnnotatedEntityMetamodel.forConcreteType(
                MapDefinitionTestTeam.class, parameterResolverFactory, messageTypeResolver, messageConverter,
                eventConverter
        );
    }

    @Test
    void mapTypedEntityMemberIsWiredWithoutAmbiguityAgainstSingleEntityChildModelDefinition() {
        // If MapEntityChildModelDefinition and SingleEntityChildModelDefinition both matched the "members" field,
        // getMetamodel() (invoked during field initialization) would have already thrown an IllegalStateException.
        assertThat(metamodel).isNotNull();
    }

    @Test
    void commandIsRoutedToMapEntryByRoutingKeyAndEventEvolvesItInPlace() {
        entityState = new MapDefinitionTestTeam("team-1");

        dispatchInstanceCommand(new AddTeamMember("team-1", "m1", "Alice"));
        dispatchInstanceCommand(new AddTeamMember("team-1", "m2", "Bob"));
        dispatchInstanceCommand(new RenameTeamMember("m2", "Bobby"));

        assertThat(entityState.getMembers()).containsOnlyKeys("m1", "m2");
        assertThat(entityState.getMembers().get("m1").getName()).isEqualTo("Alice");
        assertThat(entityState.getMembers().get("m2").getName()).isEqualTo("Bobby");
    }

    @Nested
    @DisplayName("isMemberTypeSupported / getChildTypeFromMember")
    class DefinitionUnitTests {

        @Test
        void supportsMapTypes() {
            assertThat(definition.isMemberTypeSupported(Map.class)).isTrue();
            assertThat(definition.isMemberTypeSupported(HashMap.class)).isTrue();
        }

        @Test
        void doesNotSupportListOrPlainTypes() {
            assertThat(definition.isMemberTypeSupported(List.class)).isFalse();
            assertThat(definition.isMemberTypeSupported(String.class)).isFalse();
        }

        @Test
        void resolvesValueTypeOfMapToChildType() throws NoSuchFieldException {
            Class<?> childType = definition.getChildTypeFromMember(
                    FieldOwner.class.getDeclaredField("typedMap")
            );

            assertThat(childType).isEqualTo(MapDefinitionTestTeamMember.class);
        }

        @Test
        void throwsWhenGenericValueTypeCannotBeResolved() throws NoSuchFieldException {
            assertThatThrownBy(() -> definition.getChildTypeFromMember(
                    FieldOwner.class.getDeclaredField("rawMap")
            )).isInstanceOf(AxonConfigurationException.class);
        }

        @SuppressWarnings("unused")
        static class FieldOwner {

            Map<String, MapDefinitionTestTeamMember> typedMap;
            @SuppressWarnings("rawtypes")
            Map rawMap;
        }
    }

    class MapDefinitionTestTeam {

        @SuppressWarnings("unused")
        private final String id;

        @EntityMember(routingKey = "memberId")
        private final Map<String, MapDefinitionTestTeamMember> members = new HashMap<>();

        MapDefinitionTestTeam(String id) {
            this.id = id;
        }

        @CommandHandler
        public void handle(AddTeamMember command, EventAppender appender) {
            appender.append(new TeamMemberAdded(command.teamId(), command.memberId(), command.name()));
        }

        @EventHandler
        public void on(TeamMemberAdded event) {
            members.put(event.memberId(), new MapDefinitionTestTeamMember(event.memberId(), event.name()));
        }

        public Map<String, MapDefinitionTestTeamMember> getMembers() {
            return members;
        }
    }

    class MapDefinitionTestTeamMember {

        private final String memberId;
        private String name;

        MapDefinitionTestTeamMember(String memberId, String name) {
            this.memberId = memberId;
            this.name = name;
        }

        @CommandHandler
        public void handle(RenameTeamMember command) {
            this.name = command.newName();
        }

        @SuppressWarnings("unused")
        public String getMemberId() {
            return memberId;
        }

        public String getName() {
            return name;
        }
    }

    record AddTeamMember(String teamId, String memberId, String name) {

    }

    record RenameTeamMember(String memberId, String newName) {

    }

    record TeamMemberAdded(String teamId, String memberId, String name) {

    }
}