-- The people and firms the wedding is made with. Their contact details are personal data:
-- they are kept here and nowhere else, and never written to a log.

CREATE TABLE vendor (
    id             uuid        PRIMARY KEY,
    wedding_id     uuid        NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    name           text        NOT NULL CHECK (length(btrim(name)) > 0),
    -- The same categories the plan and the budget use; a category that goes leaves the vendor without one.
    category_id    uuid        REFERENCES category (id) ON DELETE SET NULL,
    status         text        NOT NULL CHECK (status IN ('CONSIDERING', 'BOOKED', 'DROPPED')),
    contact_person text        NOT NULL DEFAULT '',
    phone          text        NOT NULL DEFAULT '',
    email          text        NOT NULL DEFAULT '',
    website        text        NOT NULL DEFAULT '' CHECK (website = '' OR website ~ '^https?://'),
    address        text        NOT NULL DEFAULT '',
    -- In the smallest unit of the wedding's currency, like every amount.
    agreed_price   bigint      CHECK (agreed_price >= 0),
    notes          text        NOT NULL DEFAULT '',
    -- Counts the saves, so that a save based on an outdated copy can be refused.
    version        integer     NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

CREATE INDEX vendor_wedding ON vendor (wedding_id);

-- What can point to a vendor. A vendor that is deleted leaves these behind, without a vendor.
ALTER TABLE entry ADD COLUMN vendor_id uuid REFERENCES vendor (id) ON DELETE SET NULL;
CREATE INDEX entry_vendor ON entry (vendor_id) WHERE vendor_id IS NOT NULL;

ALTER TABLE inspiration ADD COLUMN vendor_id uuid REFERENCES vendor (id) ON DELETE SET NULL;
CREATE INDEX inspiration_vendor ON inspiration (vendor_id) WHERE vendor_id IS NOT NULL;
