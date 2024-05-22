/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.license;

import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.ServiceLoader;

public interface LicenseServiceProvider {

    @Slf4j
    final class Impl {
        private static final Impl INSTANCE = new Impl();

        private final LicenseServiceProvider serviceProvider;

        private Impl() {
            Iterator<LicenseServiceProvider> iterator = ServiceLoader.load(LicenseServiceProvider.class).iterator();
            this.serviceProvider = iterator.next();
            if (iterator.hasNext()) {
                log.warn("Load multi license service provider, use {}.", serviceProvider.getClass().getName());
            }
        }
    }

    LicenseService get();

    static LicenseServiceProvider getDefault() {
        return Impl.INSTANCE.serviceProvider;
    }
}
