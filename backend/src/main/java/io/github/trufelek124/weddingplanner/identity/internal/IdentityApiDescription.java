package io.github.trufelek124.weddingplanner.identity.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** The caller's id comes from the access token, so it is not a parameter in the API description. */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class IdentityApiDescription {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(UserId.class);
    }
}
