package io.github.trufelek124.weddingplanner.wedding.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingDtos.DetailsRequest;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingDtos.MemberResponse;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingDtos.SetUpRequest;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingDtos.WeddingResponse;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingRepository.MemberRow;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingRepository.WeddingRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The one wedding of this installation and its two members. */
@Service
class WeddingService implements Weddings {

    private static final Logger log = LoggerFactory.getLogger(WeddingService.class);

    /** A wedding as one of its members sees it. */
    private record Seat(WeddingRow wedding, List<MemberRow> members, MemberRow mine) {}

    private final WeddingRepository repository;
    private final Clock clock;

    WeddingService(WeddingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** The first owner creates the wedding and takes the first place; the second place waits for the other. */
    @Transactional
    WeddingResponse setUp(UserId user, SetUpRequest request) {
        WeddingRow wedding = new WeddingRow(
                UUID.randomUUID(), request.date(), currency(request.currency()), timeZone(request.timeZone()));
        if (!repository.insert(wedding, clock.instant())) {
            throw new ApiException(ProblemCode.WEDDING_ALREADY_SET_UP, "The wedding is already set up.");
        }
        repository.insertMember(new MemberRow(UUID.randomUUID(), wedding.id(), 1, request.yourName().strip(), user.value()));
        repository.insertMember(new MemberRow(UUID.randomUUID(), wedding.id(), 2, request.partnerName().strip(), null));
        log.info("User {} set up wedding {}", user, wedding.id());
        return view(seatOf(user));
    }

    @Transactional
    WeddingResponse get(UserId user) {
        return view(seatOf(user));
    }

    @Transactional
    WeddingResponse update(UserId user, DetailsRequest request) {
        Seat seat = seatOf(user);
        repository.update(
                new WeddingRow(seat.wedding().id(), request.date(), currency(request.currency()), timeZone(request.timeZone())),
                clock.instant());
        return view(seatOf(user));
    }

    /** Either of the two may change either name: it is what the planner calls them, nothing more. */
    @Transactional
    WeddingResponse rename(UserId user, UUID memberId, String displayName) {
        Seat seat = seatOf(user);
        if (!repository.rename(seat.wedding().id(), memberId, displayName.strip())) {
            throw new ApiException(ProblemCode.NOT_FOUND, "The wedding has no such member.");
        }
        return view(seatOf(user));
    }

    @Override
    @Transactional
    public Membership membershipOf(UserId user) {
        Seat seat = seatOf(user);
        return new Membership(
                seat.wedding().id(),
                seat.mine().id(),
                ZoneId.of(seat.wedding().timeZone()),
                seat.wedding().weddingDate(),
                seat.members().stream().map(MemberRow::id).toList(),
                seat.members().stream().filter(member -> member.userId() != null).map(MemberRow::id).toList());
    }

    @Override
    @Transactional
    public boolean hasMember(UUID weddingId, UUID memberId) {
        return repository.members(weddingId).stream().anyMatch(member -> member.id().equals(memberId));
    }

    /** Finds the caller's place; a signed-in owner without one takes the free place. */
    private Seat seatOf(UserId user) {
        WeddingRow wedding = repository.find().orElseThrow(WeddingService::notSetUp);
        List<MemberRow> members = repository.members(wedding.id());
        Optional<MemberRow> mine = mine(members, user);
        if (mine.isEmpty()) {
            // Taking a place is rare and must not happen twice at once: hold the wedding row meanwhile.
            wedding = repository.findForUpdate().orElseThrow(WeddingService::notSetUp);
            members = repository.members(wedding.id());
            mine = mine(members, user);
            if (mine.isEmpty()) {
                MemberRow free = members.stream()
                        .filter(member -> member.userId() == null)
                        .findFirst()
                        .orElseThrow(() -> new ApiException(ProblemCode.WEDDING_FULL, "The wedding already has its two members."));
                repository.linkUser(free.id(), user.value());
                log.info("User {} joined wedding {}", user, wedding.id());
                members = repository.members(wedding.id());
                mine = mine(members, user);
            }
        }
        return new Seat(wedding, members, mine.orElseThrow());
    }

    private static Optional<MemberRow> mine(List<MemberRow> members, UserId user) {
        return members.stream().filter(member -> user.value().equals(member.userId())).findFirst();
    }

    private static WeddingResponse view(Seat seat) {
        List<MemberResponse> members = seat.members().stream()
                .map(member -> new MemberResponse(
                        member.id(), member.displayName(), member.id().equals(seat.mine().id()), member.userId() != null))
                .toList();
        WeddingRow wedding = seat.wedding();
        LocalDate date = wedding.weddingDate();
        return new WeddingResponse(wedding.id(), date, wedding.currency(), wedding.timeZone(), members);
    }

    private static String currency(String code) {
        String normalised = code.strip().toUpperCase(java.util.Locale.ROOT);
        try {
            return Currency.getInstance(normalised).getCurrencyCode();
        } catch (IllegalArgumentException unknown) {
            throw ApiException.invalidField("currency", "Use a three-letter currency code such as PLN or EUR.");
        }
    }

    private static String timeZone(String name) {
        String normalised = name.strip();
        if (!ZoneId.getAvailableZoneIds().contains(normalised)) {
            throw ApiException.invalidField("timeZone", "Use a time zone name such as Europe/Warsaw.");
        }
        return normalised;
    }

    private static ApiException notSetUp() {
        return new ApiException(ProblemCode.WEDDING_NOT_SET_UP, "The wedding is not set up yet.");
    }
}
