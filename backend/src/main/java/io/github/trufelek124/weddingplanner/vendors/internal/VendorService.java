package io.github.trufelek124.weddingplanner.vendors.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.vendors.VendorCategories;
import io.github.trufelek124.weddingplanner.vendors.VendorUse;
import io.github.trufelek124.weddingplanner.vendors.VendorUse.Use;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.VendorChange;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.VendorFields;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.NewVendor;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorRepository.Filter;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorRepository.VendorRow;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorTypes.LinksOnDelete;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The register of vendors: what may be saved about one, and what deleting one does to what points to it. */
@Service
class VendorService {

    /** Something before an @, something after it with a dot: enough to catch a slip, not a proof that it exists. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** A vendor as it is shown: what is stored, and what hangs on it. */
    record Shown(VendorRow vendor, Use use) {}

    private final VendorRepository vendors;
    private final VendorCategories categories;
    private final List<VendorUse> uses;
    private final Weddings weddings;
    private final Clock clock;

    VendorService(VendorRepository vendors, VendorCategories categories, List<VendorUse> uses, Weddings weddings, Clock clock) {
        this.vendors = vendors;
        this.categories = categories;
        this.uses = uses;
        this.weddings = weddings;
        this.clock = clock;
    }

    @Transactional
    List<Shown> list(UserId user, Filter filter) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        Map<UUID, Use> use = useByVendor(weddingId);
        return vendors.list(weddingId, filter).stream()
                .map(vendor -> new Shown(vendor, use.getOrDefault(vendor.id(), Use.NONE)))
                .toList();
    }

    @Transactional
    Shown get(UserId user, UUID id) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        return shown(weddingId, existing(weddingId, id));
    }

    @Transactional
    Shown create(UserId user, NewVendor request) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        Instant now = clock.instant();
        VendorRow vendor = checked(weddingId, UUID.randomUUID(), request, 0, now, now);
        vendors.insert(weddingId, vendor);
        return shown(weddingId, existing(weddingId, vendor.id()));
    }

    @Transactional
    Shown change(UserId user, UUID id, VendorChange request) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        VendorRow before = existing(weddingId, id);
        Instant now = clock.instant();
        VendorRow after = checked(weddingId, id, request, before.version(), before.createdAt(), now);
        if (!vendors.update(weddingId, after, request.version(), now)) {
            throw new ApiException(
                    ProblemCode.CHANGED_MEANWHILE, "Someone else changed this in the meantime. Load it again, then make your change.");
        }
        return shown(weddingId, existing(weddingId, id));
    }

    /**
     * @param links what happens to the plan items, tasks and inspirations that point to the vendor;
     *     while something does and this is null, nothing is deleted and the caller is asked to say
     */
    @Transactional
    void delete(UserId user, UUID id, LinksOnDelete links) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        existing(weddingId, id);
        if (links == null && useByVendor(weddingId).getOrDefault(id, Use.NONE).any()) {
            throw new ApiException(
                    ProblemCode.VENDOR_IN_USE,
                    "Plan items, tasks or inspirations point to this vendor. Say that they may lose it, and it is deleted.");
        }
        vendors.delete(weddingId, id);
    }

    // ------------------------------------------------------------ how a vendor is shown

    private Shown shown(UUID weddingId, VendorRow vendor) {
        return new Shown(vendor, useByVendor(weddingId).getOrDefault(vendor.id(), Use.NONE));
    }

    /** What every module that can point to a vendor says, added up per vendor. */
    private Map<UUID, Use> useByVendor(UUID weddingId) {
        Map<UUID, Use> all = new HashMap<>();
        for (VendorUse one : uses) {
            one.byVendor(weddingId).forEach((vendorId, use) -> all.merge(vendorId, use, Use::plus));
        }
        return all;
    }

    // ------------------------------------------------------------ rules

    private VendorRow existing(UUID weddingId, UUID id) {
        return vendors.find(weddingId, id).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such vendor."));
    }

    /** Applies the rules to what a person sent and returns the vendor as it will be stored. */
    private VendorRow checked(UUID weddingId, UUID id, VendorFields request, int version, Instant createdAt, Instant now) {
        List<FieldProblem> problems = new ArrayList<>();
        if (request.categoryId() != null && !categories.exists(weddingId, request.categoryId())) {
            problems.add(new FieldProblem("categoryId", "Choose one of the categories."));
        }
        String email = request.email().strip();
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) {
            problems.add(new FieldProblem("email", "Enter an e-mail address like name@example.com, or leave it empty."));
        }
        String website = "";
        if (!request.website().isBlank()) {
            Optional<String> address = webAddress(request.website().strip());
            if (address.isEmpty()) {
                problems.add(new FieldProblem("website", "Enter a web address like example.com, or leave it empty."));
            } else {
                website = address.get();
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "The request is not valid.", problems);
        }
        return new VendorRow(
                id, request.name().strip(), request.categoryId(), request.status(), request.contactPerson().strip(),
                request.phone().strip(), email, website, request.address().strip(), request.agreedPrice(),
                request.notes().strip(), version, createdAt, now);
    }

    /**
     * A web address as it is kept: with http:// or https://, the latter put in front of one that
     * was typed without. Anything else a browser might be told to open is not a vendor's website.
     */
    static Optional<String> webAddress(String typed) {
        String withScheme = typed.matches("(?i)^[a-z][a-z0-9+.-]*:.*") ? typed : "https://" + typed;
        try {
            URI uri = new URI(withScheme);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean web = scheme.equals("http") || scheme.equals("https");
            String host = uri.getHost();
            if (!web || host == null || !host.contains(".") || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(withScheme);
        } catch (URISyntaxException notAnAddress) {
            return Optional.empty();
        }
    }
}
