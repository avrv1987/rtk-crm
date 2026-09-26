WITH jobs AS (
    SELECT j.id, j.kind, j.format, j.group_by, j.status, j.row_count, j.started_at, j.finished_at, p.display_name
    FROM report_jobs j
    JOIN crm_user_profiles p ON p.id = j.owner_profile_id
    WHERE j.created_at >= :'since'::timestamptz
      AND j.created_at < :'until'::timestamptz
      AND j.started_at IS NOT NULL
      AND j.finished_at IS NOT NULL
), peak AS (
    SELECT a.started_at AS moment, count(*) AS running
    FROM jobs a
    JOIN jobs b ON b.started_at <= a.started_at AND b.finished_at > a.started_at
    GROUP BY a.started_at
    ORDER BY running DESC, a.started_at
    LIMIT 1
), running AS (
    SELECT jobs.*, peak.moment, peak.running
    FROM peak
    JOIN jobs ON jobs.started_at <= peak.moment AND jobs.finished_at > peak.moment
)
SELECT to_char(moment AT TIME ZONE 'UTC', 'HH24:MI:SS.US') AS peak_utc,
       running AS running_at_peak,
       round(extract(epoch FROM min(finished_at) OVER () - max(started_at) OVER ()) * 1000) AS common_ms,
       display_name,
       kind,
       format,
       coalesce(group_by, '-') AS group_by,
       status,
       row_count,
       to_char(started_at AT TIME ZONE 'UTC', 'HH24:MI:SS.US') AS started_utc,
       to_char(finished_at AT TIME ZONE 'UTC', 'HH24:MI:SS.US') AS finished_utc
FROM running
ORDER BY started_at;
