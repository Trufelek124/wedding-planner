import { useState, type DragEvent } from 'react'

/**
 * Lets pictures be dropped on an area of the page. Spread `handlers` on the area; `dropping` is true
 * while files hover over it. Anything dragged that is not a file is left to the browser.
 */
export function usePictureDrop(onFiles: (files: File[]) => void) {
  const [dropping, setDropping] = useState(false)

  return {
    dropping,
    handlers: {
      onDragOver(event: DragEvent) {
        if (Array.from(event.dataTransfer.types).includes('Files')) {
          event.preventDefault()
          setDropping(true)
        }
      },
      onDragLeave(event: DragEvent) {
        // Moving from one part of the area to another also "leaves" the first part.
        if (!(event.relatedTarget instanceof Node) || !event.currentTarget.contains(event.relatedTarget)) {
          setDropping(false)
        }
      },
      onDrop(event: DragEvent) {
        if (event.dataTransfer.files.length > 0) {
          event.preventDefault()
          onFiles(Array.from(event.dataTransfer.files))
        }
        setDropping(false)
      },
    },
  }
}
