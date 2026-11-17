package io.github.trufelek124.weddingplanner;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Stands in for Google in tests: serves a key set over HTTP like Google does and signs ID tokens
 * with it, so the application's real verification runs against tokens the tests control.
 */
public final class TestGoogle {

    public static final String CLIENT_ID = "test-client.apps.googleusercontent.com";

    private static final RSAKey KEY = newKey("test-key");
    private static final RSAKey STRANGER = newKey("stranger-key");
    private static final HttpServer SERVER = start();

    private TestGoogle() {}

    public static String jwksUri() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/certs";
    }

    /** A valid ID token for the address; change single claims with the builder's methods. */
    public static IdToken idToken(String email) {
        return new IdToken(email);
    }

    public static final class IdToken {

        private final JWTClaimsSet.Builder claims;
        private RSAKey signer = KEY;

        private IdToken(String email) {
            Instant now = Instant.now();
            this.claims = new JWTClaimsSet.Builder()
                    .issuer("https://accounts.google.com")
                    .audience(CLIENT_ID)
                    .subject("google-" + email)
                    .claim("email", email)
                    .claim("email_verified", true)
                    .claim("name", "Test Person")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(3600)));
        }

        public IdToken subject(String subject) {
            claims.subject(subject);
            return this;
        }

        public IdToken issuer(String issuer) {
            claims.issuer(issuer);
            return this;
        }

        public IdToken audience(String audience) {
            claims.audience(audience);
            return this;
        }

        public IdToken name(String name) {
            claims.claim("name", name);
            return this;
        }

        public IdToken claim(String name, Object value) {
            claims.claim(name, value);
            return this;
        }

        public IdToken expiresAt(Instant instant) {
            claims.expirationTime(Date.from(instant));
            return this;
        }

        /** Signed with a key Google's key set does not hold, as a forger's token would be. */
        public IdToken signedByAStranger() {
            this.signer = STRANGER;
            return this;
        }

        public String build() {
            try {
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims.build());
                jwt.sign(new RSASSASigner(signer));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static RSAKey newKey(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer start() {
        try {
            byte[] keys = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                exchange.getResponseBody().write(keys);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
