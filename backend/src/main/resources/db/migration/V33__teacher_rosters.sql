CREATE TABLE teacher_rosters (
    id UUID PRIMARY KEY,
    interaction_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    lms_course VARCHAR(300) NOT NULL,
    lms_group VARCHAR(300),
    created_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT teacher_rosters_interaction_fk
        FOREIGN KEY (interaction_id, organization_id) REFERENCES interactions(id, organization_id),
    CONSTRAINT teacher_rosters_id_organization_key UNIQUE (id, organization_id)
);

CREATE UNIQUE INDEX teacher_rosters_course_group_key
    ON teacher_rosters (interaction_id, lower(lms_course), lower(COALESCE(lms_group, '')));

CREATE TABLE teacher_roster_exports (
    id UUID PRIMARY KEY,
    roster_id UUID NOT NULL REFERENCES teacher_rosters(id) ON DELETE CASCADE,
    rows_count INTEGER NOT NULL CHECK (rows_count > 0),
    exported_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    exported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    transferred_by UUID REFERENCES crm_user_profiles(id),
    transferred_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT teacher_roster_exports_transfer_check CHECK ((transferred_at IS NULL) = (transferred_by IS NULL))
);

CREATE INDEX teacher_roster_exports_roster_idx ON teacher_roster_exports (roster_id, exported_at DESC);

CREATE TABLE teacher_roster_members (
    roster_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    contact_id UUID NOT NULL,
    export_id UUID REFERENCES teacher_roster_exports(id),
    transferred_at TIMESTAMP WITH TIME ZONE,
    added_by UUID NOT NULL REFERENCES crm_user_profiles(id),
    added_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (roster_id, contact_id),
    CONSTRAINT teacher_roster_members_roster_fk
        FOREIGN KEY (roster_id, organization_id) REFERENCES teacher_rosters(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT teacher_roster_members_contact_fk
        FOREIGN KEY (contact_id, organization_id) REFERENCES contacts(id, organization_id),
    CONSTRAINT teacher_roster_members_transfer_check CHECK (transferred_at IS NULL OR export_id IS NOT NULL)
);

CREATE INDEX teacher_roster_members_contact_idx ON teacher_roster_members (contact_id);
CREATE INDEX teacher_roster_members_export_idx ON teacher_roster_members (export_id);
