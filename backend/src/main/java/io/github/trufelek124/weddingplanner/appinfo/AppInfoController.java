package io.github.trufelek124.weddingplanner.appinfo;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AppInfoController {

    private final AppInfo info;

    AppInfoController(ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        // A run straight from an IDE has no build information.
        this.info = new AppInfo("wedding-planner", properties == null ? "development" : properties.getVersion());
    }

    @GetMapping(path = "/api/app-info", produces = MediaType.APPLICATION_JSON_VALUE)
    AppInfo appInfo() {
        return info;
    }
}
