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

package org.axonframework.common.infra;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JacksonComponentDescriptorTest extends ComponentDescriptorTestSuite {

    @Override
    ComponentDescriptor testSubject() {
        return new JacksonComponentDescriptor();
    }

    @Override
    String describeAsString(ComponentDescriptor testSubject) {
        return ((JacksonComponentDescriptor) testSubject).describe();
    }

    @BeforeEach
    void setUp() {
        testSubject = new JacksonComponentDescriptor();
    }

    @Override
    void assertDescribeNullString(String result) {
        assertJsonMatches(result, """
                {
                  "nullString" : null
                }
                """);
    }

    @Override
    void assertDescribeNullLong(String result) {
        assertJsonMatches(result, """
                {
                  "nullLong" : null
                }
                """);
    }

    @Override
    void assertDescribeNullBoolean(String result) {
        assertJsonMatches(result, """
                {
                  "nullBoolean" : null
                }
                """);
    }

    @Override
    void assertDescribeNullObject(String result) {
        assertJsonMatches(result, """
                {
                  "nullObject" : null
                }
                """);
    }

    @Override
    void assertDescribeNullMap(String result) {
        assertJsonMatches(result, """
                {
                  "nullMap" : null
                }
                """);
    }

    @Override
    void assertDescribeNullList(String result) {
        assertJsonMatches(result, """
                {
                  "nullList" : null
                }
                """);
    }

    @Test
    void describeComplexStructureShouldIncludeTypeAndId() {
        // given
        record ComplexTestComponent(
                String stringValue,
                long numberValue,
                boolean booleanValue,
                DescribableComponent component,
                List<String> simpleList,
                List<DescribableComponent> componentList,
                Map<String, String> simpleMap
        ) implements DescribableComponent {

            @Override
            public void describeTo(@NonNull ComponentDescriptor descriptor) {
                descriptor.describeProperty("stringValue", stringValue);
                descriptor.describeProperty("numberValue", numberValue);
                descriptor.describeProperty("booleanValue", booleanValue);
                descriptor.describeProperty("component", component);
                descriptor.describeProperty("simpleList", simpleList);
                descriptor.describeProperty("componentList", componentList);
                descriptor.describeProperty("simpleMap", simpleMap);
            }
        }

        var structure = new ComplexTestComponent(
                "test",
                42L,
                true,
                new SimpleTestComponent("nestedComponent", 300),
                List.of("listItem1", "listItem2"),
                List.of(
                        new SimpleTestComponent("listComponent1", 301),
                        new SimpleTestComponent("listComponent2", 302)
                ),
                Map.of("mapKey1", "mapValue1")
        );

        testSubject.describeProperty("complexStructure", structure);

        // when
        var result = describeAsString(testSubject);

        // then
        assertJsonMatchesPattern(
                result,
                """
                        {
                            "complexStructure":{
                                "_ref":"*",
                                "_type":"org.axonframework.common.infra.JacksonComponentDescriptorTest$1ComplexTestComponent",
                                "stringValue":"test",
                                "numberValue":42,
                                "booleanValue":true,
                                "component":{
                                    "_ref":"*",
                                    "_type":"org.axonframework.common.infra.ComponentDescriptorTestSuite$SimpleTestComponent",
                                    "name":"nestedComponent",
                                    "value":300
                                },
                                "simpleList":["listItem1","listItem2"],
                                "componentList":[
                                    {
                                        "_ref":"*",
                                        "_type":"org.axonframework.common.infra.ComponentDescriptorTestSuite$SimpleTestComponent",
                                        "name":"listComponent1",
                                        "value":301
                                    },
                                    {
                                        "_ref":"*",
                                        "_type":"org.axonframework.common.infra.ComponentDescriptorTestSuite$SimpleTestComponent",
                                        "name":"listComponent2",
                                        "value":302
                                    }
                                ],
                                "simpleMap":{"mapKey1":"mapValue1"}
                            }
                        }
                        """
        );
    }

    @Override
    void assertDescribeBoolean(String result) {
        assertJsonMatches(result, """
                {
                  "testName" : true
                }
                """);
    }

    @Override
    void assertDescribeLong(String result) {
        assertJsonMatches(result, """
                {
                  "testName" : 42
                }
                """
        );
    }

    @Override
    void assertDescribeString(String result) {
        assertJsonMatches(result, """
                {
                  "testName" : "testValue"
                }
                """
        );
    }

    @Override
    void assertDescribeCollectionOfDescribableComponentsShouldIncludeTypeAndId(
            String result,
            ComponentDescriptorTestSuite.SimpleTestComponent component1,
            ComponentDescriptorTestSuite.SimpleTestComponent component2
    ) {
        assertJsonMatches(result, """
                {
                  "components" : [ {
                    "_ref" : "%s",
                    "_type" : "org.axonframework.common.infra.ComponentDescriptorTestSuite$SimpleTestComponent",
                    "name" : "component1",
                    "value" : 101
                  }, {
                    "_ref" : "%s",
                    "_type" : "org.axonframework.common.infra.ComponentDescriptorTestSuite$SimpleTestComponent",
                    "name" : "component2",
                    "value" : 102
                  } ]
                }
                """.formatted(identityOf(component1), identityOf(component2))
        );
    }

    @Override
    void assertDescribeEmptyCollection(String result) {
        assertJsonMatches(result, """
                {
                  "emptyCollection" : [ ]
                }
                """
        );
    }

    @Override
    void assertDescribeCollection(String result) {
        assertJsonMatches(result, """
                {
                  "collection" : [ "value1", "value2", "value3" ]
                }
                """
        );
    }

    @Override
    void assertDescribeMapWithDescribableComponentsShouldIncludeTypeAndId(
            String result,
            ComponentDescriptorTestSuite.SimpleTestComponent component1
    ) {
        assertJsonMatches(result, """
                    {
                      "componentMap": {
                        "component1":{
                            "_ref":"%s",
                            "_type":"org.axonframework.common.infra.ComponentDescriptorTestSuite$SimpleTestComponent",
                            "name":"value1",
                            "value":201
                        }
                      }
                    }
                """.formatted(identityOf(component1))
        );
    }

    @Override
    void assertDescribeEmptyMap(String result) {
        var expected = normalizeJson("""
                                             {
                                               "emptyMap" : { }
                                             }
                                             """);
        assertEquals(expected, normalizeJson(result));
    }

    @Override
    void assertDescribeMap(String result) {
        // since maps don't guarantee order in JSON representation, we need to check both possibilities
        var expected1 = normalizeJson("""
                                              {
                                                "map" : {
                                                  "key1" : "value1",
                                                  "key2" : "value2"
                                                }
                                              }
                                              """);
        var expected2 = normalizeJson("""
                                              {
                                                "map" : {
                                                  "key2" : "value2",
                                                  "key1" : "value1"
                                                }
                                              }
                                              """);
        var normalizedResult = normalizeJson(result);
        assertTrue(
                normalizedResult.equals(expected1) || normalizedResult.equals(expected2),
                "Expected one of two possible formats but got: " + result
        );
    }

    @Override
    void assertDescribeDescribableComponentShouldIncludeTypeAndId(
            String result,
            ComponentDescriptorTestSuite.SimpleTestComponent component
    ) {
        assertJsonMatches(result, """
                {
                  "component" : {
                    "_ref" : "%s",
                    "_type" : "org.axonframework.common.infra.ComponentDescriptorTestSuite$SimpleTestComponent",
                    "name" : "componentValue",
                    "value" : 100
                  }
                }
                """.formatted(identityOf(component)));
    }

    @Override
    void assertDescribeCollectionWithCircularReferences(
            String result,
            ComponentDescriptorTestSuite.CircularReferencesTests.CircularComponent component1,
            ComponentDescriptorTestSuite.CircularReferencesTests.CircularComponent component2
    ) {
        assertJsonMatches(result, """
                {
                  "circularRefCollection": [
                    {
                      "_ref": "%s",
                      "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent",
                      "name": "Component1",
                      "dependency": {
                        "_ref": "%s",
                        "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent",
                        "name": "Component2",
                        "dependency": {
                          "$ref": "%s",
                          "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent"
                        }
                      }
                    },
                    {
                      "$ref": "%s",
                      "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent"
                    }
                  ]
                }
                """.formatted(identityOf(component1), identityOf(component2),
                              identityOf(component1), identityOf(component2))
        );
    }

    @Override
    void assertDescribeComponentWithSelfReference(
            String result,
            ComponentDescriptorTestSuite.CircularReferencesTests.CircularComponent component
    ) {
        assertJsonMatches(result, """
                {
                  "selfRef": {
                    "_ref": "%s",
                    "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent",
                    "name": "SelfReferencing",
                    "dependency": {
                      "$ref": "%s",
                      "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent"
                    }
                  }
                }
                """.formatted(identityOf(component), identityOf(component))
        );
    }

    @Override
    void assertDescribeComponentWithCircularReference(
            String result,
            ComponentDescriptorTestSuite.CircularReferencesTests.CircularComponent component1,
            ComponentDescriptorTestSuite.CircularReferencesTests.CircularComponent component2
    ) {
        assertJsonMatches(result, """
                {
                  "circularRef": {
                    "_ref": "%s",
                    "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent",
                    "name": "Component1",
                    "dependency": {
                      "_ref": "%s",
                      "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent",
                      "name": "Component2",
                      "dependency": {
                        "$ref": "%s",
                        "_type": "org.axonframework.common.infra.ComponentDescriptorTestSuite$CircularReferencesTests$CircularComponent"
                      }
                    }
                  }
                }
                """.formatted(identityOf(component1), identityOf(component2), identityOf(component1))
        );
    }

    private String normalizeJson(String json) {
        return json.replaceAll("\\s+", "");
    }

    private void assertJsonMatchesPattern(String actual, String expectedPattern) {
        String normalizedActual = normalizeJson(actual)
                .replaceAll("\"_ref\":\"\\d+\"", "\"_ref\":\"*\"");

        String normalizedExpected = normalizeJson(expectedPattern);

        assertEquals(normalizedExpected, normalizedActual,
                     "JSON does not match expected pattern");
    }

    private void assertJsonMatches(String actual, String expectedPattern) {
        String normalizedActual = normalizeJson(actual);
        String normalizedExpected = normalizeJson(expectedPattern);
        assertEquals(normalizedExpected, normalizedActual,
                     "JSON does not match expected");
    }
}