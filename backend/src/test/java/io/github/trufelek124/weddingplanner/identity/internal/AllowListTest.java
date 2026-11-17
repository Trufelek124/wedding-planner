package io.github.trufelek124.weddingplanner.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AllowListTest {

    @Test
    void allowsTheListedAddressesHoweverTheyAreWritten() {
        AllowList list = new AllowList(" Anna@Example.com ,piotr@example.com,, ");

        assertThat(list.allows("anna@example.com")).isTrue();
        assertThat(list.allows("ANNA@EXAMPLE.COM ")).isTrue();
        assertThat(list.allows("piotr@example.com")).isTrue();
    }

    @Test
    void refusesEveryoneElse() {
        AllowList list = new AllowList("anna@example.com");

        assertThat(list.allows("anna@example.org")).isFalse();
        assertThat(list.allows("anna@example.com.evil.org")).isFalse();
        assertThat(list.allows("xanna@example.com")).isFalse();
        assertThat(list.allows("anna")).isFalse();
        assertThat(list.allows("")).isFalse();
        assertThat(list.allows(null)).isFalse();
    }

    @Test
    void anEmptyListAllowsNobody() {
        for (String empty : new String[] {"", "  ", ",", " , ,"}) {
            AllowList list = new AllowList(empty);

            assertThat(list.isEmpty()).isTrue();
            assertThat(list.allows("anna@example.com")).isFalse();
            assertThat(list.allows("")).isFalse();
        }
    }
}
