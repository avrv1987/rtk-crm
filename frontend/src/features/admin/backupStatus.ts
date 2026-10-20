import type { BackupStatus } from '../../shared/api/client'
import { formatMoscowDateTime } from '../../shared/format/datetime.ts'

type Line = { text: string; warning: boolean }

const size = (bytes: number) => (
  bytes < 1024 ** 3 ? `${Math.max(1, Math.round(bytes / 1024 ** 2))} МиБ` : `${(bytes / 1024 ** 3).toFixed(1)} ГиБ`
)

const duration = (seconds: number) => (seconds < 60 ? `${seconds} с` : `${Math.floor(seconds / 60)} мин ${seconds % 60} с`)

const failureLine = (label: string, failure: BackupStatus['backupFailure']): Line[] => (
  failure === null ? [] : [{
    text: `${label}${failure.at === null ? '' : ` ${formatMoscowDateTime(failure.at)}`} не удалась: ${failure.message || 'причина в журнале копий'}.`,
    warning: true
  }]
)

// Пороги и предупреждения считает сервер (BackupStatusApiController); здесь только текст.
export const backupLines = (backup: BackupStatus): Line[] => {
  const warns = new Set(backup.warnings)
  const backupStale = warns.has('BACKUP_STALE')
  const checkStale = warns.has('RESTORE_CHECK_STALE')
  return [
    backup.lastBackupAt === null
      ? { text: 'Отметки о копии нет: копий ещё не было или стенд восстановлен из копии.', warning: true }
      : {
          text: `Последняя копия: ${formatMoscowDateTime(backup.lastBackupAt)}`
            + (backup.lastBackupBytes === null ? '' : `, ${size(backup.lastBackupBytes)}`)
            + (backupStale ? ' — больше суток назад' : '') + '.',
          warning: backupStale
        },
    ...failureLine('Последняя копия', backup.backupFailure),
    backup.lastRestoreCheckAt === null
      ? { text: 'Проверки восстановления ещё не было: запустите scripts/restore-check.sh.', warning: true }
      : {
          text: `Проверка восстановления: ${formatMoscowDateTime(backup.lastRestoreCheckAt)}`
            + (backup.lastRestoreCheckSeconds === null ? '' : `, восстановление ${duration(backup.lastRestoreCheckSeconds)}`)
            + (checkStale ? ' — больше 35 дней назад' : '') + '.',
          warning: checkStale
        },
    ...failureLine('Последняя проверка восстановления', backup.restoreCheckFailure)
  ]
}
