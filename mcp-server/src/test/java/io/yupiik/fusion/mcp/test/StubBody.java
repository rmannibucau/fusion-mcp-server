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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.http.server.api.Body;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * An in memory request payload, published in a single chunk.
 */
public record StubBody(String content) implements Body {
    @Override
    public Body cached() {
        return this; // already in memory
    }

    @Override
    public CompletionStage<String> string() {
        return completedFuture(content);
    }

    @Override
    public CompletionStage<byte[]> bytes() {
        return completedFuture(content.getBytes(UTF_8));
    }

    @Override
    public String parameter(final String name) {
        return null;
    }

    @Override
    public void subscribe(final Flow.Subscriber<? super ByteBuffer> subscriber) {
        subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override
            public void request(final long n) {
                if (done) {
                    return;
                }
                done = true;
                subscriber.onNext(ByteBuffer.wrap(content.getBytes(UTF_8)));
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }
}
