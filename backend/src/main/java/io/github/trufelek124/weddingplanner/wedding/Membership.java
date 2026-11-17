package io.github.trufelek124.weddingplanner.wedding;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in person's place in the wedding: what every other module needs to know
 * before it reads or writes anything.
 *
 * @param memberId the caller's own member
 * @param weddingDate null until the couple has fixed the day
 * @param memberIds both members of the wedding, in the order of the setup
 */
public record Membership(UUID weddingId, UUID memberId, ZoneId timeZone, LocalDate weddingDate, List<UUID> memberIds) {}
