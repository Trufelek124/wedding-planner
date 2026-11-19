package io.github.trufelek124.weddingplanner.vendors.internal;

import io.github.trufelek124.weddingplanner.api.BeanRules;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.vendors.VendorRegister;
import io.github.trufelek124.weddingplanner.vendors.VendorToAdd;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.NewVendor;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorTypes.Status;
import jakarta.validation.Validator;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Adding a vendor for another module: the request a person's form would send, held to the same rules. */
@Component
class VendorRegisterOfTheWedding implements VendorRegister {

    private final VendorService vendors;
    private final Validator validator;

    VendorRegisterOfTheWedding(VendorService vendors, Validator validator) {
        this.vendors = vendors;
        this.validator = validator;
    }

    @Override
    @Transactional
    public UUID addVendor(UserId user, VendorToAdd vendor) {
        NewVendor request = new NewVendor(
                vendor.name(), vendor.categoryId(), Status.CONSIDERING, "", text(vendor.phone()), text(vendor.email()),
                text(vendor.website()), "", null, text(vendor.notes()));
        BeanRules.require(validator, request);
        return vendors.create(user, request).vendor().id();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
