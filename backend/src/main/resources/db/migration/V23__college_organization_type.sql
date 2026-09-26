DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'organizations'::regclass
          AND conname = 'organizations_type_check'
          AND pg_get_constraintdef(oid) LIKE '%COLLEGE%'
    ) THEN
        ALTER TABLE organizations DROP CONSTRAINT IF EXISTS organizations_type_check;
        ALTER TABLE organizations ADD CONSTRAINT organizations_type_check
            CHECK (type IN ('UNIVERSITY', 'SCHOOL', 'COLLEGE'));
    END IF;
END
$$;
