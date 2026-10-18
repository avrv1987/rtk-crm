import type { BackupStatus } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime.ts'

// Копия делается раз в сутки (scripts/backup-scheduled.sh); старше двух суток — повод проверить расписание.
const BACKUP_STALE_HOURS = 48

export const backupText = (backup: BackupStatus, now = Date.now()) => {
  if (backup.lastBackupAt === null) {
    return { text: 'Отметки о копии нет: копий ещё не было или стенд восстановлен из копии.', stale: true }
  }
  const stale = now - Date.parse(backup.lastBackupAt) > BACKUP_STALE_HOURS * 3_600_000
  return {
    text: `Последняя копия: ${formatMoscowDateTime(backup.lastBackupAt)}${stale ? ' — больше двух суток назад' : ''}.`,
    stale
  }
}
