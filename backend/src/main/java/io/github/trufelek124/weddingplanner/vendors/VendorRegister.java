package io.github.trufelek124.weddingplanner.vendors;

import io.github.trufelek124.weddingplanner.identity.UserId;
import java.util.UUID;

/**
 * How another module adds a vendor on a person's behalf: through the very code that adds one by hand, with the same
 * checks and the same messages next to the same fields.
 */
public interface VendorRegister {

    /**
     * Adds the vendor to the caller's wedding, as one that is being considered.
     *
     * @return the id of the vendor that was made
     * @throws io.github.trufelek124.weddingplanner.api.ApiException with BAD_REQUEST and a message per field when
     *     what would be made is not valid; nothing is made then
     */
    UUID addVendor(UserId user, VendorToAdd vendor);
}
