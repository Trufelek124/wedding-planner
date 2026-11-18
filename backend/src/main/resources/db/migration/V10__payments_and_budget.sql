-- Money. A task can carry an amount: then it is a payment, and it is paid when it is done.
-- Amounts are whole numbers of the smallest unit of the wedding's currency (grosze), never fractions.

ALTER TABLE entry
    ADD COLUMN amount       bigint CHECK (amount > 0),
    ADD COLUMN payment_kind text   CHECK (payment_kind IN ('DEPOSIT', 'INSTALMENT', 'FINAL')),
    -- The day it was paid, as the couple's calendar had it; completed_at says when it was ticked off.
    ADD COLUMN paid_on      date,
    ADD CONSTRAINT entry_only_a_task_carries_money CHECK (kind = 'TASK' OR amount IS NULL),
    ADD CONSTRAINT entry_payment_kind_needs_an_amount CHECK (amount IS NOT NULL OR payment_kind IS NULL),
    ADD CONSTRAINT entry_paid_when_a_payment_is_done CHECK ((paid_on IS NOT NULL) = (amount IS NOT NULL AND status = 'DONE'));

-- The budget's sums look only at the entries that carry money.
CREATE INDEX entry_payments ON entry (wedding_id) WHERE amount IS NOT NULL;

-- What the couple means to spend on everything. No row means nothing was said yet.
CREATE TABLE budget (
    wedding_id uuid        PRIMARY KEY REFERENCES wedding (id) ON DELETE CASCADE,
    total      bigint      CHECK (total >= 0),
    -- Counts the saves of the total and of the planned amounts together.
    version    integer     NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL
);

-- What they mean to spend on one category. A category that goes takes its planned amount along.
CREATE TABLE budget_plan (
    category_id uuid   PRIMARY KEY REFERENCES category (id) ON DELETE CASCADE,
    amount      bigint NOT NULL CHECK (amount >= 0)
);
