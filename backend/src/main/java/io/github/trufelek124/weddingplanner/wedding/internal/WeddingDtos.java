package io.github.trufelek124.weddingplanner.wedding.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the wedding endpoints take and give. Records that hold a name hide it in toString(). */
final class WeddingDtos {

    private WeddingDtos() {}

    @Schema(name = "WeddingSetup", description = "The first owner's answers at the setup.")
    record SetUpRequest(
            @Schema(description = "The wedding day; null while it is not fixed.", nullable = true) LocalDate date,
            @Schema(description = "ISO 4217 code of the currency all amounts are in, for example PLN.") @NotBlank String currency,
            @Schema(description = "IANA time zone the wedding's dates are read in, for example Europe/Warsaw.") @NotBlank
                    String timeZone,
            @Schema(description = "What the planner calls the person setting it up.") @NotBlank @Size(max = 80) String yourName,
            @Schema(description = "What the planner calls the other person.") @NotBlank @Size(max = 80) String partnerName) {

        @Override
        public String toString() {
            return "WeddingSetup[hidden]";
        }
    }

    @Schema(name = "WeddingDetails")
    record DetailsRequest(
            @Schema(description = "The wedding day; null while it is not fixed.", nullable = true) LocalDate date,
            @NotBlank String currency,
            @NotBlank String timeZone) {}

    @Schema(name = "MemberName")
    record RenameRequest(@NotBlank @Size(max = 80) String displayName) {

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
