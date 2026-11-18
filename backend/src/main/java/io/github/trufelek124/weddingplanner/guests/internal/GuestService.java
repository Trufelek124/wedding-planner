package io.github.trufelek124.weddingplanner.guests.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.BySide;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.ByStatus;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.GuestChange;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.GuestCounts;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.GuestFields;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.HeadCount;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.NewGuest;
import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.Counted;
import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.Filter;
import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.GuestRow;
import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.HouseholdRow;
import io.github.trufelek124.weddingplanner.guests.internal.GuestTypes.Status;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The guest list: who is on it, with whom they come, where they stand, and how many that makes. */
@Service
class GuestService {

    /** Something before an @, something after it with a dot: enough to catch a slip, not a proof that it exists. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final String HOUSEHOLD_NAME_TAKEN = "There is a household of that name already.";

    private final GuestRepository guests;
    private final Weddings weddings;
    private final Clock clock;

    GuestService(GuestRepository guests, Weddings weddings, Clock clock) {
        this.guests = guests;
        this.weddings = weddings;
        this.clock = clock;
    }

    // ------------------------------------------------------------ guests

    @Transactional
    List<GuestRow> list(UserId user, Filter filter) {
        return guests.list(weddings.membershipOf(user).weddingId(), filter);
    }

    @Transactional
    GuestRow get(UserId user, UUID id) {
        return existing(weddings.membershipOf(user).weddingId(), id);
    }

    @Transactional
    GuestRow create(UserId user, NewGuest request) {
        Membership membership = weddings.membershipOf(user);
        Instant now = clock.instant();
        GuestRow guest = checked(membership, UUID.randomUUID(), request, 0, now, now);
        guests.insert(membership.weddingId(), guest);
        return existing(membership.weddingId(), guest.id());
    }

    @Transactional
    GuestRow change(UserId user, UUID id, GuestChange request) {
        Membership membership = weddings.membershipOf(user);
        GuestRow before = existing(membership.weddingId(), id);
        Instant now = clock.instant();
        GuestRow after = checked(membership, id, request, before.version(), before.createdAt(), now);
        if (!guests.update(membership.weddingId(), after, request.version(), now)) {
            throw new ApiException(
                    ProblemCode.CHANGED_MEANWHILE, "Someone else changed this in the meantime. Load it again, then make your change.");
        }
        return existing(membership.weddingId(), id);
    }

    /** An answer came in: the status alone is set, in one action, without sending the whole guest. */
    @Transactional
    GuestRow setStatus(UserId user, UUID id, Status status) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        if (!guests.setStatus(weddingId, id, status, clock.instant())) {
            throw noSuchGuest();
        }
        return existing(weddingId, id);
    }

    @Transactional
    void delete(UserId user, UUID id) {
        if (!guests.delete(weddings.membershipOf(user).weddingId(), id)) {
            throw noSuchGuest();
        }
    }

    /**
     * The head count. Every status and every side is listed, also with nobody in it, so that a
     * client never has to guess from a missing line.
     */
    @Transactional
    GuestCounts counts(UserId user) {
        Membership membership = weddings.membershipOf(user);
        List<Counted> counted = guests.counts(membership.weddingId());
        List<UUID> sides = new ArrayList<>(membership.memberIds());
        sides.add(null);

        List<BySide> bySide = new ArrayList<>();
        for (UUID side : sides) {
            List<ByStatus> itsStatuses = new ArrayList<>();
            HeadCount all = HeadCount.NOBODY;
            for (Status status : Status.values()) {
                HeadCount here = counted.stream()
                        .filter(one -> Objects.equals(one.sideMemberId(), side) && one.status() == status)
                        .map(one -> HeadCount.of(one.guests(), one.plusOnes(), one.children()))
                        .reduce(HeadCount.NOBODY, HeadCount::plus);
                itsStatuses.add(new ByStatus(status, here));
                all = all.plus(here);
            }
            bySide.add(new BySide(side, all, itsStatuses));
        }
        List<ByStatus> byStatus = new ArrayList<>();
        HeadCount total = HeadCount.NOBODY;
        for (int at = 0; at < Status.values().length; at++) {
            int place = at;
            HeadCount here = bySide.stream().map(side -> side.byStatus().get(place).count()).reduce(HeadCount.NOBODY, HeadCount::plus);
            byStatus.add(new ByStatus(Status.values()[at], here));
            total = total.plus(here);
        }
        return new GuestCounts(total, byStatus, bySide);
    }

    // ------------------------------------------------------------ households

    @Transactional
    List<HouseholdRow> households(UserId user) {
        return guests.households(weddings.membershipOf(user).weddingId());
    }

    @Transactional
    HouseholdRow addHousehold(UserId user, String name) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        UUID id = UUID.randomUUID();
        if (!guests.insertHousehold(weddingId, id, name.strip())) {
            throw ApiException.invalidField("name", HOUSEHOLD_NAME_TAKEN);
        }
        return household(weddingId, id);
    }

    @Transactional
    HouseholdRow renameHousehold(UserId user, UUID id, String name) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        int renamed = guests.renameHousehold(weddingId, id, name.strip());
        if (renamed < 0) {
            throw ApiException.invalidField("name", HOUSEHOLD_NAME_TAKEN);
        }
        if (renamed == 0) {
            throw noSuchHousehold();
        }
        return household(weddingId, id);
    }

    /** Its guests stay on the list, on their own. */
    @Transactional
    void deleteHousehold(UserId user, UUID id) {
        if (!guests.deleteHousehold(weddings.membershipOf(user).weddingId(), id)) {
            throw noSuchHousehold();
        }
    }

    private HouseholdRow household(UUID weddingId, UUID id) {
        return guests.households(weddingId).stream().filter(one -> one.id().equals(id)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------ rules

    private GuestRow existing(UUID weddingId, UUID id) {
        return guests.find(weddingId, id).orElseThrow(GuestService::noSuchGuest);
    }

    /** Applies the rules to what a person sent and returns the guest as it will be stored. */
    private GuestRow checked(Membership membership, UUID id, GuestFields request, int version, Instant createdAt, Instant now) {
        List<FieldProblem> problems = new ArrayList<>();
        if (request.sideMemberId() != null && !membership.memberIds().contains(request.sideMemberId())) {
            problems.add(new FieldProblem("sideMemberId", "Choose one of you, or both."));
        }
        if (request.householdId() != null && !guests.householdExists(membership.weddingId(), request.householdId())) {
            problems.add(new FieldProblem("householdId", "Choose one of the households."));
        }
        String email = request.email().strip();
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            problems.add(new FieldProblem("email", "Enter an e-mail address like name@example.com, or leave it empty."));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "The request is not valid.", problems);
        }
        return new GuestRow(
                id, request.name().strip(), request.sideMemberId(), request.householdId(), request.status(), request.plusOne(),
                request.children(), request.dietaryNeeds().strip(), request.needsAccommodation(), request.phone().strip(), email,
                request.notes().strip(), version, createdAt, now);
    }

    private static ApiException noSuchGuest() {
        return new ApiException(ProblemCode.NOT_FOUND, "There is no such guest.");
    }

    private static ApiException noSuchHousehold() {
        return new ApiException(ProblemCode.NOT_FOUND, "There is no such household.");
    }
}
