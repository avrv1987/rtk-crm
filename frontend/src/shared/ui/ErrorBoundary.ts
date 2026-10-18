import { Component, createElement, type ErrorInfo, type ReactNode } from 'react'

export const homeAddress = '#/'

// Без обёртки ошибка в любом компоненте размонтирует всё дерево и оставляет белый экран. Вёрстка повторяет
// SessionScreen, но без его импорта: так экран не зависит от кода, который мог упасть.
export const ErrorFallback = () => createElement(
  'main',
  { className: 'session-screen' },
  createElement(
    'section',
    { className: 'session-card', role: 'alert', 'aria-labelledby': 'error-title' },
    createElement('p', { className: 'eyebrow' }, 'CRM ИТ Школы РТК'),
    createElement('h1', { id: 'error-title' }, 'Что-то пошло не так'),
    createElement('p', null, 'Экран не удалось показать. Введённое в формах хранится в этой вкладке и вернётся после обновления.'),
    createElement(
      'div',
      { className: 'session-help' },
      createElement('button', { type: 'button', onClick: () => window.location.reload() }, 'Обновить'),
      createElement('button', {
        type: 'button',
        className: 'button--secondary',
        onClick: () => {
          window.location.hash = homeAddress
          window.location.reload()
        }
      }, 'На главную')
    )
  )
)

type Props = { children: ReactNode }
type State = { failed: boolean }

export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error, info.componentStack)
  }

  render() {
    return this.state.failed ? createElement(ErrorFallback) : this.props.children
  }
}
