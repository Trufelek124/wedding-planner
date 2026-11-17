package io.github.trufelek124.weddingplanner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.fixture.FixtureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * Each direct sub-package of the application's package is a module. A module may use
 * the types in another module's own package, never those in its sub-packages.
 */
class ModularityTest {

    @Test
    void noModuleReachesIntoAnothersInternals() {
        ApplicationModules.of(WeddingPlannerApplication.class).verify();
    }

    @Test
    void theCheckFailsWhenAModuleReachesIntoAnothersInternals() {
        // The fixture is a tiny application in the test sources whose module "beta"
        // uses a type from "alpha.internal". If this stops throwing, the rule above checks nothing.
        ApplicationModules fixture = ApplicationModules.of(FixtureApplication.class, location -> true);

        assertThatThrownBy(fixture::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("AlphaInternals");
    }
}
