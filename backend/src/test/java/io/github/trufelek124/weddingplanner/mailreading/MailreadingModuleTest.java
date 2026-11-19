package io.github.trufelek124.weddingplanner.mailreading;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.WeddingPlannerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * The module reads the mail: it uses the module that holds the noted messages, the suggestions, the wedding's facts, the
 * vendors, the plan's categories and the API's own types, each only through that module's own package, and nothing uses it.
 */
class MailreadingModuleTest {

    @Test
    void theModuleUsesOnlyTheModulesItReadsFromAndNothingUsesIt() {
        ApplicationModules modules = ApplicationModules.of(WeddingPlannerApplication.class);
        var reading = modules.getModuleByName("mailreading").orElseThrow();

        assertThat(reading.getBasePackage().getName()).isEqualTo("io.github.trufelek124.weddingplanner.mailreading");
        assertThat(reading.contains(MailTriage.class)).isTrue();
        assertThat(reading.contains(MailExtraction.class)).isTrue();
        var uses = reading.getDirectDependencies(modules).stream().map(dependency -> dependency.getTargetModule().getIdentifier().toString()).collect(java.util.stream.Collectors.toSet());
        assertThat(uses).isSubsetOf("mail", "suggestions", "wedding", "vendors", "planning", "api");
        assertThat(uses).contains("mail", "suggestions");
        modules.stream().filter(module -> !module.getIdentifier().toString().equals("mailreading")).forEach(module ->
                assertThat(module.getDirectDependencies(modules).containsModuleNamed("mailreading")).as(module.getDisplayName()).isFalse());
    }
}
