ALTER TABLE agreements ADD COLUMN planned_kind VARCHAR(16);
ALTER TABLE agreements ADD COLUMN planned_on DATE;
ALTER TABLE agreements ADD COLUMN planned_base_until DATE;

ALTER TABLE agreements ADD CONSTRAINT agreements_planned_kind_check CHECK (planned_kind IN ('SIGNING', 'RENEWAL'));
ALTER TABLE agreements ADD CONSTRAINT agreements_plan_pair CHECK ((planned_kind IS NULL) = (planned_on IS NULL));

CREATE INDEX agreements_planned_on_idx ON agreements (planned_on);
CREATE INDEX agreements_valid_until_idx ON agreements (valid_until);
