interface PictureChooserProps {
  label: string
  disabled?: boolean
  onFiles: (files: File[]) => void
}

/**
 * A button that opens the device's own chooser for pictures. The control is the file input itself,
 * so a keyboard and a screen reader meet one control with one name; only its looks are the label's.
 */
export function PictureChooser({ label, disabled = false, onFiles }: PictureChooserProps) {
  return (
    <label className="button button-quiet file-button" data-disabled={disabled || undefined}>
      <input
        type="file"
        className="visually-hidden"
        accept="image/jpeg,image/png,image/gif,image/webp"
        multiple
        disabled={disabled}
        onChange={(event) => {
          const files = Array.from(event.target.files ?? [])
          // Emptied, so that choosing the same file again is a change again.
          event.target.value = ''
          if (files.length > 0) {
            onFiles(files)
          }
        }}
      />
      {label}
    </label>
  )
}
