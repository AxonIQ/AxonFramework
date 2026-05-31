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

package org.axonframework.extension.micronaut.util;

import org.junit.jupiter.api.extension.*;
//import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

///**
// * Junit 5 extension which fires up a Mysql test container before the test class. After all tests have run, it will tear
// * it down.
// * <p>
// * If you want behavior that creates a container before every individual test, this extension does not support that.
// */
//public class MysqlTestContainerExtension extends MySQLContainer<MysqlTestContainerExtension>
//        implements BeforeAllCallback, AfterAllCallback {
//
//    private static MysqlTestContainerExtension container;
//
//    public MysqlTestContainerExtension() {
//        super("mysql:8.0");
//    }
//
//    public static MysqlTestContainerExtension getInstance() {
//        if (container == null) {
//            container = new MysqlTestContainerExtension();
//        }
//        return container;
//    }
//
//    public DataSource asDataSource() {
//        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource(container.getJdbcUrl(),
//                                                                                      container.getUsername(),
//                                                                                      container.getPassword());
//        driverManagerDataSource.setDriverClassName(container.getDriverClassName());
//        return driverManagerDataSource;
//    }
//
//    @Override
//    public void beforeAll(ExtensionContext extensionContext) throws Exception {
//        MysqlTestContainerExtension.getInstance().start();
//    }
//
//    @Override
//    public void afterAll(ExtensionContext extensionContext) throws Exception {
//        MysqlTestContainerExtension.getInstance().stop();
//    }
//}
