package io.github.trufelek124.weddingplanner.wedding;

import io.github.trufelek124.weddingplanner.identity.UserId;

/** How the other modules reach the wedding. All data of the planner hangs off it. */
public interface Weddings {

    /**
     * The caller's membership. A signed-in owner who is not a member yet takes the free place.
     *
     * @throws io.github.trufelek124.weddingplanner.api.ApiException with WEDDING_NOT_SET_UP while
     *     there is no wedding, and WEDDING_FULL for a third person
     */
    Membership membershipOf(UserId user);

    /** Whether this member belongs to this wedding. */
    boolean hasMember(java.util.UUID weddingId, java.util.UUID memberId);
}
