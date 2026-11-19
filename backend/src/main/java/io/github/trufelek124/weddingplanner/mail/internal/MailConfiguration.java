package io.github.trufelek124.weddingplanner.mail.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
class MailConfiguration {

    /** In front of everything else that could write the request line into a log. */
    @Bean
    FilterRegistrationBean<QueryHidingFilter> returnAddressQueryHidden() {
        FilterRegistrationBean<QueryHidingFilter> registration = new FilterRegistrationBean<>(new QueryHidingFilter());
        registration.addUrlPatterns(MailSetup.RETURN_PATH);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
