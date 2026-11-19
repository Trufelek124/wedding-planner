package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Made-up key and token throughout: the key is the digits and letters of a test, the token says it is not real. */
class TokenVaultTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
    private static final String TOKEN = "test-refresh-not-real-1//0gAbCdEfGhIjK";
    private static final UUID ANNA = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID PIOTR = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private final TokenVault vault = new TokenVault(KEY);

    @Test
    void whatWasSealedOpensAgainForTheSameRow() {
        byte[] sealed = vault.seal(TOKEN, TokenVault.memberAad(ANNA));

        assertThat(vault.open(sealed, TokenVault.memberAad(ANNA))).contains(TOKEN);
    }

    @Test
    void theSealedBytesCarryAVersionAndANonceAndNeverTheTokenInPlain() {
        byte[] sealed = vault.seal(TOKEN, TokenVault.memberAad(ANNA));

        assertThat(sealed[0]).isEqualTo((byte) 1);
        assertThat(sealed).hasSize(1 + 12 + TOKEN.length() + 16);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("test-refresh").doesNotContain("0gAbCd");
    }

    @Test
    void theSameTokenSealedTwiceDiffers() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            byte[] sealed = vault.seal(TOKEN, TokenVault.memberAad(ANNA));
            assertThat(seen.add(java.util.HexFormat.of().formatHex(sealed, 1, 13))).as("a nonce is never used twice").isTrue();
        }
        assertThat(vault.seal(TOKEN, TokenVault.memberAad(ANNA))).isNotEqualTo(vault.seal(TOKEN, TokenVault.memberAad(ANNA)));
    }

    @Test
    void aChangeOfAnySingleByteMakesItUnreadable() {
        byte[] sealed = vault.seal(TOKEN, TokenVault.memberAad(ANNA));

        for (int at = 0; at < sealed.length; at++) {
            byte[] changed = sealed.clone();
            changed[at] ^= 0x01;
            assertThat(vault.open(changed, TokenVault.memberAad(ANNA))).as("byte " + at).isEmpty();
        }
    }

    @Test
    void whatIsCutShortOrExtendedIsUnreadable() {
        byte[] sealed = vault.seal(TOKEN, TokenVault.memberAad(ANNA));

        assertThat(vault.open(java.util.Arrays.copyOf(sealed, sealed.length - 1), TokenVault.memberAad(ANNA))).isEmpty();
        assertThat(vault.open(java.util.Arrays.copyOf(sealed, sealed.length + 1), TokenVault.memberAad(ANNA))).isEmpty();
        assertThat(vault.open(new byte[0], TokenVault.memberAad(ANNA))).isEmpty();
        assertThat(vault.open(null, TokenVault.memberAad(ANNA))).isEmpty();
        assertThat(vault.open(new byte[] {1, 2, 3}, TokenVault.memberAad(ANNA))).isEmpty();
    }

    @Test
    void aTokenMovedToAnotherMembersRowDoesNotOpen() {
        byte[] sealed = vault.seal(TOKEN, TokenVault.memberAad(ANNA));

        assertThat(vault.open(sealed, TokenVault.memberAad(PIOTR))).isEmpty();
        assertThat(vault.open(sealed, new byte[0])).isEmpty();
    }

    @Test
    void anotherKeyDoesNotOpenIt() {
        byte[] sealed = vault.seal(TOKEN, TokenVault.memberAad(ANNA));

        assertThat(new TokenVault(OTHER_KEY).open(sealed, TokenVault.memberAad(ANNA))).isEmpty();
    }

    @Test
    void aVersionItDoesNotKnowIsNotOpened() {
        byte[] sealed = vault.seal(TOKEN, TokenVault.memberAad(ANNA));
        sealed[0] = 2;

        assertThat(vault.open(sealed, TokenVault.memberAad(ANNA))).isEmpty();
    }

    @Test
    void aKeyThatIsNot32BytesIsNoKeyAndTheMessageDoesNotSayWhatItWas() {
        byte[] short31 = "0123456789abcdef0123456789abcde".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> new TokenVault(short31)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("0123456789abcdef");
        assertThatThrownBy(() -> new TokenVault(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itPrintsNothingOfItself() {
        assertThat(vault.toString()).isEqualTo("TokenVault[]");
    }
}
