import { useEffect, useRef, useState } from 'react'
import type { Picture, PictureSize } from './inspirationsApi.ts'
import { pictureAddress } from './pictures.ts'

interface InspirationPictureProps {
  inspirationId: string
  picture: Picture
  size: PictureSize
  /** What the picture shows, for someone who cannot see it. */
  alt: string
  /** Takes the shape of the picture itself; otherwise it fills the frame the page gives it. */
  ownShape?: boolean
}

/** How far outside the window a picture already starts loading. */
const AHEAD = '400px'

/**
 * One picture of an inspiration. It keeps its shape while the bytes are on their way, and on a long
 * board it only fetches them once it comes near the window.
 */
export function InspirationPicture({ inspirationId, picture, size, alt, ownShape = false }: InspirationPictureProps) {
  const place = useRef<HTMLDivElement>(null)
  const [near, setNear] = useState(typeof IntersectionObserver === 'undefined')
  const [address, setAddress] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (near || place.current === null) {
      return
    }
    const watcher = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setNear(true)
        }
      },
      { rootMargin: AHEAD },
    )
    watcher.observe(place.current)
    return () => watcher.disconnect()
  }, [near])

  useEffect(() => {
    if (!near) {
      return
    }
    let wanted = true
    pictureAddress(inspirationId, picture.id, size)
      .then((found) => {
        if (wanted) {
          setAddress(found)
        }
      })
      .catch(() => {
        if (wanted) {
          setFailed(true)
        }
      })
    return () => {
      wanted = false
    }
  }, [near, inspirationId, picture.id, size])

  // Its shape is known before its bytes are: the page does not jump when they arrive.
  const shape =
    ownShape && picture.width !== null && picture.height !== null ? { aspectRatio: `${picture.width} / ${picture.height}` } : undefined

  return (
    <div className="picture" ref={place} style={shape}>
      {address !== null ? (
        <img src={address} alt={alt} />
      ) : failed ? (
        <span className="picture-missing">This picture could not be loaded.</span>
      ) : (
        <span className="visually-hidden">Loading a picture…</span>
      )}
    </div>
  )
}
