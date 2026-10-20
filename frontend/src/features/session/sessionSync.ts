const sessionKey = 'rtk-crm:session-profile'

export const announceSession = (profileId: string | null) => {
  try {
    window.localStorage.setItem(sessionKey, profileId ?? '')
  } catch {
    return
  }
}

// signedOut: в другой вкладке нажали «Выйти» (пустое значение); иначе там вошёл другой сотрудник.
export const watchSessionElsewhere = (profileId: string, onChange: (signedOut: boolean) => void) => {
  const listener = (event: StorageEvent) => {
    if (event.key === sessionKey && event.newValue !== null && event.newValue !== profileId) {
      onChange(event.newValue === '')
    }
  }
  window.addEventListener('storage', listener)
  return () => window.removeEventListener('storage', listener)
}
