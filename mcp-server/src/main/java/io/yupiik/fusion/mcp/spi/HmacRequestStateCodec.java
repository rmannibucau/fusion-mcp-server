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
package io.yupiik.fusion.mcp.spi;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A {@link MCPRequestStateCodec} signing the state with a HMAC-SHA256 of the configured secret
 * ({@code fusion.mcp.requestStateSecret}): the token is {@code base64url(payload).base64url(hmac)} where the payload
 * embeds the state and a short expiry - so a tampered <b>or expired</b> token is rejected when it is decoded back.
 * <p>
 * The expiry bounds the replay window of a {@code requestState}, as the {@code 2026-07-28} MRTR spec asks for.
 * It is only {@link #isActive() active} when a secret is configured, else the default opaque codec is used.
 */
@ApplicationScoped
public class HmacRequestStateCodec implements MCPRequestStateCodec {
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    /**
     * Default lifetime of a {@code requestState} token, in milliseconds - short enough to bound replays.
     */
    private static final long DEFAULT_TTL_MS = 300_000L;

    private final String secret;

    protected HmacRequestStateCodec() {
        this(null);
    }

    public HmacRequestStateCodec(final MCPConfiguration configuration) {
        this.secret = configuration == null ? null : configuration.requestStateSecret();
    }

    @Override
    public String encode(final Object state) {
        return encode(state, DEFAULT_TTL_MS);
    }

    /**
     * @param state the state to sign.
     * @param ttlMs the lifetime of the token, in milliseconds.
     * @return a token expiring {@code ttlMs} from now, already expired when {@code ttlMs} is negative.
     */
    String encode(final Object state, final long ttlMs) {
        if (state == null) {
            return empty();
        }
        final var payload = (System.currentTimeMillis() + ttlMs) + "|" + state;
        final var bytes = payload.getBytes(UTF_8);
        return BASE64.encodeToString(bytes) + "." + BASE64.encodeToString(hmac(bytes));
    }

    @Override
    public Object decode(final String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        final var dot = token.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("Invalid request state token");
        }
        final var payloadBytes = BASE64_DECODER.decode(token.substring(0, dot));
        if (!MessageDigest.isEqual(hmac(payloadBytes), BASE64_DECODER.decode(token.substring(dot + 1)))) {
            throw new IllegalArgumentException("Tampered request state token");
        }
        final var payload = new String(payloadBytes, UTF_8);
        final var sep = payload.indexOf('|');
        if (sep < 0) {
            return payload;
        }
        try {
            final var exp = Long.parseLong(payload.substring(0, sep));
            if (exp < System.currentTimeMillis()) {
                throw new IllegalArgumentException("Expired request state token");
            }
            return payload.substring(sep + 1);
        } catch (final NumberFormatException nfe) {
            return payload; // no expiry marker, keep the state unchanged
        }
    }

    @Override
    public boolean isActive() {
        return secret != null && !secret.isBlank();
    }

    @Override
    public String empty() {
        return "";
    }

    private byte[] hmac(final byte[] payload) {
        try {
            final var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (final Exception e) { // NoSuchAlgorithmException and InvalidKeyException, both impossible here
            throw new IllegalStateException(e);
        }
    }
}
