ALTER TABLE interaction_events ADD COLUMN contact_channel VARCHAR(16);
ALTER TABLE interaction_events ADD CONSTRAINT interaction_events_contact_channel_check
    CHECK (contact_channel IS NULL OR contact_channel IN ('MEETING', 'CALL', 'EMAIL', 'MESSENGER', 'EVENT'));

ALTER TABLE interaction_event_contacts DROP CONSTRAINT interaction_event_contacts_change_type_check;
ALTER TABLE interaction_event_contacts ADD CONSTRAINT interaction_event_contacts_change_type_check
    CHECK (change_type IN ('ADDED', 'REMOVED', 'PARTICIPANT'));

ALTER TABLE interaction_issues ADD COLUMN last_comment VARCHAR(1000);
ALTER TABLE interaction_issues ADD COLUMN last_comment_by UUID REFERENCES crm_user_profiles(id);
ALTER TABLE interaction_issues ADD COLUMN last_comment_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE interaction_event_views (
    profile_id UUID NOT NULL REFERENCES crm_user_profiles(id),
    event_id UUID NOT NULL REFERENCES interaction_events(id),
    viewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (profile_id, event_id)
);
