package io.github.trufelek124.weddingplanner.wedding.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the wedding endpoints take and give. Records that hold a name hide it in toString(). */
final class WeddingDtos {

    // These messages are shown to people next to the field, so they say what to do.
    private static final String CHOOSE_CURRENCY = "Choose a currency.";
    private static final String CHOOSE_TIME_ZONE = "Choose a time zone.";
    private static final String TOO_LONG = "Use at most 80 characters.";

    private WeddingDtos() {}

    @Schema(name = "WeddingSetup", description = "The first owner's answers at the setup.")
    record SetUpRequest(
            @Schema(description = "The wedding day; null while it is not fixed.", nullable = true) LocalDate date,
            @Schema(description = "ISO 4217 code of the currency all amounts are in, for example PLN.")
                    @NotBlank(message = CHOOSE_CURRENCY)
                    String currency,
            @Schema(description = "IANA time zone the wedding's dates are read in, for example Europe/Warsaw.")
                    @NotBlank(message = CHOOSE_TIME_ZONE)
                    String timeZone,
            @Schema(description = "What the planner calls the person setting it up.")
                    @NotBlank(message = "Enter your name.")
                    @Size(max = 80, message = TOO_LONG)
                    String yourName,
            @Schema(description = "What the planner calls the other person.")
                    @NotBlank(message = "Enter your partner's name.")
                    @Size(max = 80, message = TOO_LONG)
                    String partnerName) {

        @Override
        public String toString() {
            return "WeddingSetup[hidden]";
        }
    }

    @Schema(name = "WeddingDetails")
    record DetailsRequest(
            @Schema(description = "The wedding day; null while it is not fixed.", nullable = true) LocalDate date,
            @NotBlank(message = CHOOSE_CURRENCY) String currency,
            @NotBlank(message = CHOOSE_TIME_ZONE) String timeZone) {}

    @Schema(name = "MemberName")
    record RenameRequest(
            @NotBlank(message = "Enter a name.") @Size(max = 80, message = TOO_LONG) String displayName) {

        @Override
        public String toString() {
            return "MemberName[hidden]";
        }
    }

    @Schema(name = "WeddingMember", description = "One of the two people the wedding belongs to.")
    record MemberResponse(
            UUID id,
            String displayName,
            @Schema(description = "Whether this is the person asking.") boolean you,
            @Schema(description = "Whether this person has signed in to the planner yet.") boolean signedIn) {

        @Override
        public String toString() {
            return "WeddingMember[id=" + id + "]";
        }
    }

    @Schema(name = "Wedding")
    record WeddingResponse(
            UUID id,
            @Schema(description = "The wedding day; null while it is not fixed.", nullable = true) LocalDate date,
            String currency,
            String timeZone,
            @Schema(description = "The two people, in the order of the setup.") List<MemberResponse> members) {

        @Override
        public String toString() {
            return "Wedding[id=" + id + "]";
        }
    }
}
