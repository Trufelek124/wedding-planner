import { useId, type ReactNode } from 'react'

interface FieldProps {
  label: string
  /** Shown after the label when the field may be left empty. */
  optional?: boolean
  hint?: string
  /** What is wrong with the value, if anything. */
  error?: string | undefined
  /** Renders the control; it must spread the given attributes on its input or select. */
  children: (control: { id: string; 'aria-describedby': string | undefined; 'aria-invalid': boolean }) => ReactNode
}

/** A labelled form control with its hint and its error wired up for screen readers. */
export function Field({ label, optional = false, hint, error, children }: FieldProps) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')

  return (
    <div className="field stack-tight">
      <label htmlFor={id}>
        {label}
        {optional && <span className="optional"> (optional)</span>}
      </label>
      {children({ id, 'aria-describedby': describedBy || undefined, 'aria-invalid': Boolean(error) })}
      {hint && (
        <p className="hint" id={hintId}>
          {hint}
        </p>
      )}
      {error && (
        <p className="error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  )
}
