package io.github.trufelek124.weddingplanner.webapp;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Answers a path that is no file with index.html when the path is a page of the web app
 * (for example /plan/12 opened directly or reloaded). API paths and paths that name a
 * file stay "not found".
 */
class ClientRouteFallbackResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
        Resource file = super.getResource(resourcePath, location);
        if (file != null) {
            return file;
        }
        return isClientRoute(resourcePath) ? super.getResource("index.html", location) : null;
    }

    static boolean isClientRoute(String path) {
        if (path.startsWith("api/") || path.equals("api") || path.startsWith("actuator/") || path.equals("actuator")) {
            return false;
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }
}
