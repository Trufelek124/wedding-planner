package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.util.List;

/**
 * Where a new idea arrives on the canvas (S08 section 2), worked out from the places of the ideas already there and
 * nothing else: no database, no clock.
 */
final class CanvasPlacement {

    /** How far a place may lie from 0 either way. */
    static final int LIMIT = 100_000;

    static final int SLOT_WIDTH = 220;
    static final int SLOT_HEIGHT = 260;
    /** A row holds six: the seventh would lie more than five slots from the left edge. */
    static final int SLOTS_ACROSS = 6;

    record Place(int x, int y) {}

    private CanvasPlacement() {}

    /**
     * One slot to the right of the card that lies lowest (of several equally low, the right-most), unless that lies
     * too far from the left edge of everything, beyond the limit, or within a slot of another card; then a new row
     * at the left edge, 260 below the lowest. Never beyond the limits.
     */
    static Place next(List<Place> existing) {
        if (existing.isEmpty()) {
            return new Place(0, 0);
        }
        int left = Integer.MAX_VALUE;
        Place found = existing.getFirst();
        for (Place place : existing) {
            left = Math.min(left, place.x());
            if (place.y() > found.y() || (place.y() == found.y() && place.x() > found.x())) {
                found = place;
            }
        }
        Place lowest = found;
        int besideX = lowest.x() + SLOT_WIDTH;
        boolean fits = besideX - left <= (SLOTS_ACROSS - 1) * SLOT_WIDTH
                && besideX <= LIMIT
                && existing.stream().noneMatch(other -> overlaps(besideX, lowest.y(), other));
        if (fits) {
            return new Place(besideX, lowest.y());
        }
        return new Place(left, Math.min(lowest.y() + SLOT_HEIGHT, LIMIT));
    }

    /** Whether a card at x, y would lie within a slot of the other one. */
    private static boolean overlaps(int x, int y, Place other) {
        return Math.abs(x - other.x()) < SLOT_WIDTH && Math.abs(y - other.y()) < SLOT_HEIGHT;
    }
}
