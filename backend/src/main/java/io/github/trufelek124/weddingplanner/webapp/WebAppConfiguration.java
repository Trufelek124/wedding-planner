package io.github.trufelek124.weddingplanner.webapp;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the built web app from the directory named by APP_WEB_DIR, so the web app and
 * the API share one origin. Without that variable the backend serves the API only.
 */
@Configuration
class WebAppConfiguration implements WebMvcConfigurer {

    private final String webDir;

    WebAppConfiguration(@Value("${app.web-dir:}") String webDir) {
        this.webDir = webDir.strip();
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (!webDir.isEmpty()) {
            // The resource handler below is never asked for the empty path, so the root is forwarded.
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (webDir.isEmpty()) {
            return;
        }
        Path root = Path.of(webDir).toAbsolutePath().normalize();

        // The build puts a content hash into every file name under assets/, so they never change.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(new FileSystemResource(root.resolve("assets") + "/"))
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());

        // No resolver cache: it would remember every path ever asked for, without limit.
        registry.addResourceHandler("/**")
                .addResourceLocations(new FileSystemResource(root + "/"))
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new ClientRouteFallbackResolver());
    }
}
