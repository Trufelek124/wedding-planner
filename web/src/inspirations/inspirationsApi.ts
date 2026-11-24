import { api, ApiError, unwrap, type Schemas } from '../api/client.ts'
import type { paths } from '../api/generated/schema.ts'

export type Inspiration = Schemas['Inspiration']
export type NewInspiration = Schemas['NewInspiration']
export type InspirationChange = Schemas['InspirationChange']
export type Picture = Schemas['Picture']
export type Board = Schemas['Board']
export type TagCount = Schemas['TagCount']
export type Source = Inspiration['source']
/** What a list of inspirations can be narrowed down by. */
export type InspirationFilter = NonNullable<paths['/api/inspirations']['get']['parameters']['query']>
export type PictureSize = 'THUMBNAIL' | 'FULL'

/** What the server takes: the same limits it enforces, so that a person is told before a long upload. */
export const MOST_PICTURES = 10
export const MOST_BYTES_PER_PICTURE = 15 * 1024 * 1024

// ---------------------------------------------------------------- boards

export async function listBoards(signal?: AbortSignal): Promise<Board[]> {
  return unwrap(await api.GET('/api/boards', { signal }))
}

export async function addBoard(name: string): Promise<Board> {
  return unwrap(await api.POST('/api/boards', { body: { name } }))
}

export async function renameBoard(id: string, name: string): Promise<Board> {
  return unwrap(await api.PUT('/api/boards/{id}', { params: { path: { id } }, body: { name } }))
}

/**
 * @param inspirations for a board with inspirations: what happens to them
 * @throws ApiError `board-has-inspirations` when the board has some and nothing was said
 */
export async function deleteBoard(id: string, inspirations?: 'KEEP' | 'DELETE'): Promise<void> {
  const { error } = await api.DELETE('/api/boards/{id}', { params: { path: { id }, query: { inspirations } } })
  if (error !== undefined) {
    unwrap({ error })
  }
}

// ---------------------------------------------------------------- inspirations

export async function listInspirations(filter: InspirationFilter, signal?: AbortSignal): Promise<Inspiration[]> {
  return unwrap(await api.GET('/api/inspirations', { params: { query: filter }, signal }))
}

export async function listTags(signal?: AbortSignal): Promise<TagCount[]> {
  return unwrap(await api.GET('/api/inspirations/tags', { signal }))
}

export async function getInspiration(id: string, signal?: AbortSignal): Promise<Inspiration> {
  return unwrap(await api.GET('/api/inspirations/{id}', { params: { path: { id } }, signal }))
}

export async function createInspiration(inspiration: NewInspiration): Promise<Inspiration> {
  return unwrap(await api.POST('/api/inspirations', { body: inspiration }))
}

/** @throws ApiError `changed-meanwhile` when someone else saved the inspiration after it was loaded */
export async function changeInspiration(id: string, change: InspirationChange): Promise<Inspiration> {
  return unwrap(await api.PUT('/api/inspirations/{id}', { params: { path: { id } }, body: change }))
}

/** Puts an idea's card somewhere else on the canvas. The later move wins; the idea's version is not touched. */
export async function placeInspiration(id: string, place: { x: number; y: number }): Promise<Inspiration> {
  return unwrap(await api.PUT('/api/inspirations/{id}/place', { params: { path: { id } }, body: place }))
}

export async function deleteInspiration(id: string): Promise<void> {
  const { error } = await api.DELETE('/api/inspirations/{id}', { params: { path: { id } } })
  if (error !== undefined) {
    unwrap({ error })
  }
}

// ---------------------------------------------------------------- pictures

/** Sends pictures to an inspiration. The server keeps all of them or, if it refuses one, none. */
export async function addPictures(inspirationId: string, files: readonly File[]): Promise<Inspiration> {
  return unwrap(
    await api.POST('/api/inspirations/{inspirationId}/pictures', {
      params: { path: { inspirationId } },
      // The description of the API calls the files strings; what is sent is the form below.
      body: { files: [] },
      bodySerializer: () => {
        const form = new FormData()
        for (const file of files) {
          form.append('files', file)
        }
        return form
      },
    }),
  )
}

export async function removePicture(inspirationId: string, pictureId: string): Promise<Inspiration> {
  return unwrap(
    await api.DELETE('/api/inspirations/{inspirationId}/pictures/{pictureId}', {
      params: { path: { inspirationId, pictureId } },
    }),
  )
}

/** The bytes of a picture. They are only handed out with the access token, so an `<img>` cannot ask by address. */
export async function fetchPicture(inspirationId: string, pictureId: string, size: PictureSize): Promise<Blob> {
  const { data, error } = await api.GET('/api/inspirations/{inspirationId}/pictures/{pictureId}', {
    params: { path: { inspirationId, pictureId }, query: { size } },
    parseAs: 'blob',
  })
  if (data !== undefined) {
    return data
  }
  throw error === undefined ? new Error('The server sent no picture') : new ApiError(error)
}

/**
 * Why these files cannot be sent, in words for the person; null when they can.
 * @param already how many pictures the inspiration has
 */
export function whyNot(files: readonly File[], already: number): string | null {
  if (already + files.length > MOST_PICTURES) {
    return `An inspiration holds at most ${MOST_PICTURES} pictures.`
  }
  const tooLarge = files.find((file) => file.size > MOST_BYTES_PER_PICTURE)
  if (tooLarge !== undefined) {
    return `“${tooLarge.name}” is larger than 15 MB. Choose a smaller picture.`
  }
  const other = files.find((file) => file.type !== '' && !file.type.startsWith('image/'))
  if (other !== undefined) {
    return `“${other.name}” is not a picture.`
  }
  return null
}

/** What to tell a person when the server did not take their pictures. */
export function uploadProblem(error: unknown): string {
  if (error instanceof ApiError) {
    const said = error.fieldMessage('files')
    if (said !== undefined) {
      return said
    }
    if (error.is('urn:wedding-planner:problem:upload-too-large')) {
      return 'That is more than the planner takes at once. Add fewer or smaller pictures.'
    }
  }
  return 'The pictures could not be saved. Try again.'
}
