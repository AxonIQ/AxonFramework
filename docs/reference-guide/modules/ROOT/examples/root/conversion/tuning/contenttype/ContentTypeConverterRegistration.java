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
package root.conversion.tuning.contenttype;

import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;

import tools.jackson.databind.ObjectMapper;

class ContentTypeConverterRegistration {

    // tag::register-custom-content-type-converter[]
    public GeneralConverter converterWithCustomContentTypeConverter() {
        ChainingContentTypeConverter contentTypeConverter = new ChainingContentTypeConverter();
        contentTypeConverter.registerConverter(new MyContentTypeConverter());

        return new DelegatingGeneralConverter(new JacksonConverter(new ObjectMapper(), contentTypeConverter));
    }
    // end::register-custom-content-type-converter[]
}
