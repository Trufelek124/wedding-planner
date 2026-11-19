package io.github.trufelek124.weddingplanner.identity.internal;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Who may call what. Everything under /api needs a valid access token, except the paths
 * listed in {@link #PUBLIC_API}. Everything outside /api is the web app and the health check.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
class SecurityConfiguration implements WebMvcConfigurer {

    /**
     * API paths that answer without a sign-in. Adding one is a security decision: keep the list short.
     * The mail connection's return address is where Google sends a browser back, which carries no access token:
     * that exact path (no wildcard) and nothing else of the mail module.
     */
    static final String[] PUBLIC_API = {"/api/auth/**", "/api/app-info", "/api/mail/gmail/return"};

    /**
     * What a page of the web app may load and where it may send data: itself, plus exactly what
     * Google's sign-in button needs (its script, its frame, its style sheet and its own calls).
     * Styles written into the page are allowed because that button sets some; scripts never are.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://accounts.google.com/gsi/client",
            "frame-src https://accounts.google.com/gsi/",
            "connect-src 'self' https://accounts.google.com/gsi/",
            "style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style",
            "img-src 'self' data: blob:",
            "font-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    SecurityFilterChain security(HttpSecurity http, SecurityProblems problems) throws Exception {
        http
                // No server-side session and no cookie that authorises API calls: every request carries
                // its access token in a header, which another site cannot make a browser send.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(policy -> policy.policyDirectives(CONTENT_SECURITY_POLICY))
                        .crossOriginOpenerPolicy(opener -> opener.policy(CrossOriginOpenerPolicy.SAME_ORIGIN_ALLOW_POPUPS))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_API).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(tokens -> tokens
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, failure) -> problems.unauthenticated(request, response))
                        .accessDeniedHandler((request, response, denial) -> problems.forbidden(request, response)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> problems.unauthenticated(request, response))
                        .accessDeniedHandler((request, response, denial) -> problems.forbidden(request, response)));
        return http.build();
    }

    @Bean
    JwtDecoder accessTokenDecoder(AccessTokens accessTokens) {
        return accessTokens.decoder();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new UserIdArgumentResolver());
    }
}
