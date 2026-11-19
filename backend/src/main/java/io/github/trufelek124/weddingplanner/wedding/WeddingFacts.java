package io.github.trufelek124.weddingplanner.wedding;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * What a part of the server that works without a signed-in person needs to know of a wedding.
 *
 * @param weddingDate null until the couple has fixed the day
 * @param currency the ISO 4217 code all amounts are in
 * @param timeZone where the wedding takes place
 */
public record WeddingFacts(LocalDate weddingDate, String currency, ZoneId timeZone) {}
