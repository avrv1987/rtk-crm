type CameraInputProps = {
  disabled?: boolean
  onFile: (file: File) => void
}

// На телефоне камера открывается сразу; на компьютере кнопка скрыта стилем, обычный выбор файла остаётся.
export const CameraInput = ({ disabled, onFile }: CameraInputProps) => (
  <label className="camera-input">
    <span>Снять документ камерой</span>
    <input
      type="file"
      accept="image/*"
      capture="environment"
      disabled={disabled}
      onChange={(event) => {
        const file = event.target.files?.item(0)
        if (file) {
          onFile(file)
        }
        event.target.value = ''
      }}
    />
  </label>
)
