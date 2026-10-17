const sessionKey = 'rtk-crm:session-profile'

export const announceSession = (profileId: string | null) => {
  try {
    window.localStorage.setItem(sessionKey, profileId ?? '')
  } catch {
    return
  }
}

export const watchSessionElsewhere = (profileId: string, onChange: () => void) => {
  const listener = (event: StorageEvent) => {
    if (event.key === sessionKey && event.newValue !== null && event.newValue !== profileId) {
      onChange()
    }
  }
  window.addEventListener('storage', listener)
  return () => window.removeEventListener('storage', listener)
}
