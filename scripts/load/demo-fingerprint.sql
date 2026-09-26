SELECT format('%s %s %s', t.name, t.row_count, t.digest)
FROM (
    SELECT 'teams' AS name, count(*) AS row_count, md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) AS digest FROM teams x
    UNION ALL SELECT 'crm_user_profiles', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM crm_user_profiles x
    UNION ALL SELECT 'organizations', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM organizations x
    UNION ALL SELECT 'contacts', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM contacts x
    UNION ALL SELECT 'interactions', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM interactions x
    UNION ALL SELECT 'interaction_stages', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM interaction_stages x
    UNION ALL SELECT 'interaction_stage_transitions', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM interaction_stage_transitions x
    UNION ALL SELECT 'interaction_contacts', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM interaction_contacts x
    UNION ALL SELECT 'interaction_events', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM interaction_events x
    UNION ALL SELECT 'product_agreements', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM product_agreements x
    UNION ALL SELECT 'attachments', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM attachments x
    UNION ALL SELECT 'directions', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM directions x
    UNION ALL SELECT 'programs', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM programs x
    UNION ALL SELECT 'vendors', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM vendors x
    UNION ALL SELECT 'products', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM products x
    UNION ALL SELECT 'workflow_templates', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM workflow_templates x
    UNION ALL SELECT 'workflow_template_stages', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM workflow_template_stages x
    UNION ALL SELECT 'workflow_template_transitions', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM workflow_template_transitions x
    UNION ALL SELECT 'organization_assignment_events', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM organization_assignment_events x
    UNION ALL SELECT 'organization_team_events', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM organization_team_events x
    UNION ALL SELECT 'crm_profile_events', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM crm_profile_events x
    UNION ALL SELECT 'report_jobs', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM report_jobs x
    UNION ALL SELECT 'source_mappings', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM source_mappings x
    UNION ALL SELECT 'catalog_imports', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM catalog_imports x
    UNION ALL SELECT 'command_idempotency_records', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM command_idempotency_records x
    UNION ALL SELECT 'audit_events', count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), '')) FROM audit_events x
) t
ORDER BY t.name;
