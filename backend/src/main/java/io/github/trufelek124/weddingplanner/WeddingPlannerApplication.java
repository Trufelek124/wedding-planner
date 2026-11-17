package io.github.trufelek124.weddingplanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One deployable application. Each direct sub-package of this package is a module:
 * the types in a module's own package are its interface, everything in its
 * sub-packages is internal to it (checked by ModularityTest).
 */
@SpringBootApplication
public class WeddingPlannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeddingPlannerApplication.class, args);
    }
}
