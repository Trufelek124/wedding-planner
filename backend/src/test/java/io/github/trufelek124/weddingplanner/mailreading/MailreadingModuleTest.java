package io.github.trufelek124.weddingplanner.mailreading;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.WeddingPlannerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** The new module stands by itself: it uses no other module, and (until the pipeline that follows) no module uses it. */
class MailreadingModuleTest {

    @Test
    void theModuleUsesNoOtherModuleAndNothingUsesIt() {
        ApplicationModules modules = ApplicationModules.of(WeddingPlannerApplication.class);
        var reading = modules.getModuleByName("mailreading").orElseThrow();

        assertThat(reading.getBasePackage().getName()).isEqualTo("io.github.trufelek124.weddingplanner.mailreading");
        assertThat(reading.contains(MailTriage.class)).isTrue();
        assertThat(reading.contains(MailExtraction.class)).isTrue();
        assertThat(reading.getDirectDependencies(modules).isEmpty()).isTrue();
        modules.stream().filter(module -> !module.getIdentifier().toString().equals("mailreading")).forEach(module ->
                assertThat(module.getDirectDependencies(modules).containsModuleNamed("mailreading")).as(module.getDisplayName()).isFalse());
    }
}
