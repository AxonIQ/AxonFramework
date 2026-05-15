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

package org.axonframework.integrationtests.cache;

import org.axonframework.common.caching.Cache;
import org.axonframework.common.caching.JCacheAdapter;
import org.junit.jupiter.api.*;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.spi.CachingProvider;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * {@link javax.cache.Cache} specific implementation of the {@link CachingIntegrationTestSuite}.
 *
 * @author Gerard Klijs
 */
@Disabled("TODO #3727")
class JCacheIT extends CachingIntegrationTestSuite {

    private CacheManager cacheManager;

    @Override
    @BeforeEach
    void setUp() {
        CachingProvider provider = Caching.getCachingProvider();
        cacheManager = provider.getCacheManager();
        super.setUp();
    }

    @AfterEach
    void tearDown() {
        cacheManager.close();
    }

    @Override
    public Cache buildCache(String name) {
        return new JCacheAdapter(createCache(name));
    }

    private javax.cache.Cache<Object, Object> createCache(String name) {
        MutableConfiguration<Object, Object> configuration =
                new MutableConfiguration<>()
                        .setTypes(Object.class, Object.class)
                        .setStoreByValue(false)
                        .setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(new Duration(SECONDS, 1)));
        return cacheManager.createCache(name, configuration);
    }
}
