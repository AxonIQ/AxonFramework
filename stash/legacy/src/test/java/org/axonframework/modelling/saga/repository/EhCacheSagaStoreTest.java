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

package org.axonframework.modelling.saga.repository;

import org.axonframework.common.caching.Cache;
import org.axonframework.common.caching.EhCacheAdapter;
import org.ehcache.CacheManager;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.core.Ehcache;
import org.ehcache.core.EhcacheManager;
import org.ehcache.core.config.DefaultConfiguration;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Concrete implementation of the {@link CachingSagaStoreTest} using the {@link EhCacheAdapter}.
 *
 * @author Steven van Beelen
 * @author Gerard Klijs
 */
class EhCacheSagaStoreTest extends CachingSagaStoreTest {

    private CacheManager cacheManager;
    private org.ehcache.core.Ehcache ehCache;

    @AfterEach
    void tearDown() {
        cacheManager.close();
    }

    @Override
    Cache sagaCache() {
        if (ehCache == null) {
            buildEhCache();
        }
        return new EhCacheAdapter(ehCache);
    }

    @Override
    Cache associationCache() {
        if (ehCache == null) {
            buildEhCache();
        }
        return new EhCacheAdapter(ehCache);
    }

    private void buildEhCache() {
        Map<String, CacheConfiguration<?, ?>> caches = new HashMap<>();
        DefaultConfiguration config = new DefaultConfiguration(caches, null);
        cacheManager = new EhcacheManager(config);
        cacheManager.init();
        ehCache = (Ehcache) cacheManager
                .createCache(
                        "test",
                        CacheConfigurationBuilder
                                .newCacheConfigurationBuilder(
                                        Object.class,
                                        Object.class,
                                        ResourcePoolsBuilder.heap(100L).build())
                                .build());
    }
}
