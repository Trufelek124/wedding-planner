package io.github.trufelek124.weddingplanner.vendors;

import java.util.UUID;

/**
 * A vendor as another module proposes it, with the fields of the vendor's own form that it can know. What is left
 * out is null (or empty), and is checked as the form checks it.
 */
public record VendorToAdd(UUID categoryId, String name, String phone, String email, String website, String notes) {

    /** A name and contact details may come out of someone's mail: nothing of them is printed. */
    @Override
    public String toString() {
        return "VendorToAdd[]";
    }
}
