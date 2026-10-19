export const attachmentLimitMegabytes = 20
export const attachmentNameLimit = 255

// Проверка до отправки: файл больше лимита или с именем длиннее 255 символов сервер всё равно не примет, а длинное
// кириллическое имя раньше давало ложное «файл слишком большой» или «связь прервалась».
export const attachmentFileProblem = (file: { name: string; size: number }): string | null => {
  if (file.size > attachmentLimitMegabytes * 1024 * 1024) {
    return `Файл больше ${attachmentLimitMegabytes} МБ. Выберите файл меньшего размера.`
  }
  if (file.name.length > attachmentNameLimit) {
    return `Имя файла длиннее ${attachmentNameLimit} символов (сейчас ${file.name.length}). Переименуйте файл и выберите его снова.`
  }
  return null
}
