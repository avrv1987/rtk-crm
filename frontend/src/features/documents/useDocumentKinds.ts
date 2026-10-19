import { useEffect, useState } from 'react'
import { listDocumentKinds, type DocumentKind } from './documentsApi'

/** Справочник видов документов; пока не загружен (или при ошибке) — пустой список, подписи берутся из kindName. */
export const useDocumentKinds = (): DocumentKind[] => {
  const [kinds, setKinds] = useState<DocumentKind[]>([])
  useEffect(() => {
    let active = true
    listDocumentKinds().then((value) => {
      if (active) {
        setKinds(value)
      }
    }).catch(() => undefined)
    return () => {
      active = false
    }
  }, [])
  return kinds
}
