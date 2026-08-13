/*
 * Copyright (c) 2025 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.fusion.mcp.test;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The implementation logs the details of what it drops - an unknown client response, an expired session, a client
 * going away mid-stream - with a message supplier, which is only evaluated when the level is enabled.
 * <p>
 * Tests use this to also run those suppliers, i.e. to check they do not throw whatever they are given.
 */
public final class Loggers {
    private Loggers() {
        // no-op
    }

    /**
     * Runs {@code task} with the logger of {@code type} at {@link Level#FINEST}, restoring it afterwards.
     */
    public static void atFinest(final Class<?> type, final Runnable task) {
        atFinest(type, () -> {
            task.run();
            return null;
        });
    }

    /**
     * @return what {@code task} returned, it ran with the logger of {@code type} at {@link Level#FINEST}.
     */
    public static <T> T atFinest(final Class<?> type, final Supplier<T> task) {
        final var logger = Logger.getLogger(type.getName());
        final var previous = logger.getLevel();
        logger.setLevel(Level.FINEST);
        try {
            return task.get();
        } finally {
            logger.setLevel(previous);
        }
    }
}
