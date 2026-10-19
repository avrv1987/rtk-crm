// В тестах под node сборочной константы нет.
export const appVersion = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'не определена'
