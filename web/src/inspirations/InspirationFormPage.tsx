import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { newId } from '../api/ids.ts'
import { listEntries, type Entry } from '../planning/planningApi.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { listVendors, type Vendor } from '../vendors/vendorsApi.ts'
import { InspirationPicture } from './InspirationPicture.tsx'
import {
  addPictures,
  changeInspiration,
  createInspiration,
  deleteInspiration,
  getInspiration,
  listBoards,
  MOST_PICTURES,
  removePicture,
  uploadProblem,
  whyNot,
  type Board,
  type Inspiration,
  type Picture,
} from './inspirationsApi.ts'
import { nameOf, tagsFrom, tagsToText } from './labels.ts'
import { PictureChooser } from './PictureChooser.tsx'
import { forgetPicturesOf } from './pictures.ts'
import { usePictureDrop } from './usePictureDrop.ts'

/** The page for adding an inspiration (`/inspirations/new`) and for changing one (`/inspirations/:id`). */
export function InspirationFormPage() {
  const { id } = useParams()

  const inspiration = useLoaded(id ?? 'new', (signal) => (id === undefined ? Promise.resolve(null) : getInspiration(id, signal)))
  const boards = useLoaded('boards', listBoards)
  const planItems = useLoaded('plan-items', (signal) => listEntries({ kind: 'PLAN_ITEM', sort: 'TITLE' }, signal))
  const vendors = useLoaded('vendors', (signal) => listVendors({}, signal))

  if (
    inspiration.state.status === 'failed' ||
    boards.state.status === 'failed' ||
    planItems.state.status === 'failed' ||
    vendors.state.status === 'failed'
  ) {
    return (
      <PageRetry
        message="This could not be loaded. It may have been deleted. Try again, or go back to the inspirations."
        onRetry={() => {
          inspiration.retry()
          boards.retry()
          planItems.retry()
          vendors.retry()
        }}
      />
    )
  }
  if (
    inspiration.state.status === 'loading' ||
    boards.state.status === 'loading' ||
    planItems.state.status === 'loading' ||
    vendors.state.status === 'loading'
  ) {
    return <PageWaiting what="Loading…" />
  }

  const existing = inspiration.state.data
  return (
    <InspirationForm
      // Another inspiration starts the form again. Loading the latest version does too: the page waits in between.
      key={existing?.id ?? 'new'}
      loaded={existing}
      boards={boards.state.data}
      planItems={planItems.state.data}
      vendors={vendors.state.data}
      onReload={inspiration.retry}
    />
  )
}

interface InspirationFormProps {
  loaded: Inspiration | null
  boards: Board[]
  planItems: Entry[]
  vendors: Vendor[]
  onReload: () => void
}

/** What the page that led here said: where to return to, and the board or the vendor that was being looked at. */
function cameWith(state: unknown): { back: string | null; boardId: string | null; vendorId: string | null } {
  const said = typeof state === 'object' && state !== null ? (state as Record<string, unknown>) : {}
  return {
    back: typeof said.back === 'string' ? said.back : null,
    boardId: typeof said.boardId === 'string' ? said.boardId : null,
    vendorId: typeof said.vendorId === 'string' ? said.vendorId : null,
  }
}

function InspirationForm({ loaded, boards, planItems, vendors, onReload }: InspirationFormProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const came = cameWith(location.state)
  const back = came.back ?? '/inspirations'

  // The inspiration as the server holds it. Its pictures change at once, apart from the fields below.
  // A new inspiration's id is chosen when the form opens: saving twice, after no answer came, adds it once.
  const [newInspirationId] = useState(newId)
  const [existing, setExisting] = useState(loaded)
  const [url, setUrl] = useState(loaded?.url ?? '')
  const [boardId, setBoardId] = useState(loaded?.boardId ?? (boards.some((board) => board.id === came.boardId) ? came.boardId : null) ?? '')
  const [title, setTitle] = useState(loaded?.title ?? '')
  const [note, setNote] = useState(loaded?.note ?? '')
  const [tags, setTags] = useState(tagsToText(loaded?.tags ?? []))
  const [planItemId, setPlanItemId] = useState(loaded?.planItemId ?? '')
  const [vendorId, setVendorId] = useState(
    loaded?.vendorId ?? (loaded === null && vendors.some((vendor) => vendor.id === came.vendorId) ? came.vendorId : null) ?? '',
  )
  /** For a new inspiration: the pictures that go with it when it is saved. */
  const [chosen, setChosen] = useState<File[]>([])

  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<ApiError | 'unreachable' | 'nothing' | null>(null)
  const [picturesProblem, setPicturesProblem] = useState<string | null>(null)
  const [asking, setAsking] = useState<null | 'delete' | { picture: string }>(null)
  const [large, setLarge] = useState<string | null>(null)

  function fieldError(field: string): string | undefined {
    return failure instanceof ApiError ? failure.fieldMessage(field) : undefined
  }
  /** The server names the tag that is wrong by its place in the list: "tags[2]". */
  const tagsError =
    failure instanceof ApiError ? failure.problem.fieldProblems?.find((problem) => problem.field.startsWith('tags'))?.message : undefined

  function fields() {
    return {
      url: url.trim() === '' ? null : url.trim(),
      boardId: boardId === '' ? null : boardId,
      title: title.trim(),
      note: note.trim(),
      tags: tagsFrom(tags),
      planItemId: planItemId === '' ? null : planItemId,
      vendorId: vendorId === '' ? null : vendorId,
    }
  }

  async function attempt(action: () => Promise<void>) {
    setBusy(true)
    setFailure(null)
    setPicturesProblem(null)
    try {
      await action()
    } catch (error) {
      if (error instanceof PicturesRefused) {
        setPicturesProblem(error.message)
      } else {
        setFailure(error instanceof ApiError ? error : 'unreachable')
      }
    }
    setBusy(false)
  }

  function save(event: FormEvent) {
    event.preventDefault()
    if (existing === null && url.trim() === '' && chosen.length === 0) {
      setFailure('nothing')
      return
    }
    void attempt(async () => {
      if (existing !== null) {
        await changeInspiration(existing.id, { version: existing.version, ...fields() })
      } else {
        const created = await createInspiration({ id: newInspirationId, ...fields() })
        if (chosen.length > 0) {
          try {
            await addPictures(created.id, chosen)
          } catch (error) {
            // All or nothing: without its pictures it is not what was meant to be saved.
            await deleteInspiration(created.id).catch(() => undefined)
            throw new PicturesRefused(uploadProblem(error))
          }
        }
      }
      await navigate(back)
    })
  }

  /** Pictures chosen or dropped: kept at once for an inspiration that exists, set aside for a new one. */
  function takePictures(files: File[]) {
    const refusal = whyNot(files, existing === null ? chosen.length : existing.pictures.length)
    if (refusal !== null) {
      setPicturesProblem(refusal)
      return
    }
    if (existing === null) {
      setPicturesProblem(null)
      setChosen([...chosen, ...files])
      return
    }
    void attempt(async () => {
      try {
        setExisting(await addPictures(existing.id, files))
      } catch (error) {
        throw new PicturesRefused(uploadProblem(error))
      }
    })
  }

  function removeOne(picture: Picture) {
    if (existing === null) return
    void attempt(async () => {
      try {
        setExisting(await removePicture(existing.id, picture.id))
      } catch {
        throw new PicturesRefused('The picture could not be removed. Try again.')
      }
      forgetPicturesOf(existing.id, picture.id)
      setAsking(null)
      if (large === picture.id) {
        setLarge(null)
      }
    })
  }

  function remove() {
    if (existing === null) return
    void attempt(async () => {
      await deleteInspiration(existing.id)
      forgetPicturesOf(existing.id)
      await navigate(back)
    })
  }

  const drop = usePictureDrop(takePictures)

  const changedMeanwhile = failure instanceof ApiError && failure.is('urn:wedding-planner:problem:changed-meanwhile')
  const general =
    failure === 'unreachable'
      ? 'The server cannot be reached. Check your connection and try again.'
      : failure === 'nothing'
        ? 'Paste a link or choose at least one picture.'
        : failure !== null && !changedMeanwhile && (failure.problem.fieldProblems ?? []).length === 0
          ? (failure.problem.detail ?? 'That did not work. Try again.')
          : null

  const name = existing === null ? '' : nameOf(existing)
  const largePicture = existing?.pictures.find((picture) => picture.id === large)
  const room = MOST_PICTURES - (existing === null ? chosen.length : existing.pictures.length)

  return (
    <div className="form-page" data-dropping={drop.dropping || undefined} {...drop.handlers}>
      <h1>{existing === null ? 'Add an inspiration' : 'Change this inspiration'}</h1>

      {existing !== null && existing.url !== null && (
        <p>
          <a href={existing.url} target="_blank" rel="noopener noreferrer">
            Open the original
            <span className="visually-hidden"> (in a new tab)</span>
          </a>
        </p>
      )}

      {changedMeanwhile && (
        <div role="alert" className="alert stack">
          <p>Someone else changed this in the meantime, so your change was not saved.</p>
          <button type="button" className="button button-quiet" onClick={onReload}>
            Load the latest version
          </button>
        </div>
      )}
      {general !== null && (
        <p role="alert" className="alert">
          {general}
        </p>
      )}

      <section className="stack" aria-labelledby="pictures-heading">
        <h2 id="pictures-heading">Pictures</h2>
        {existing !== null &&
          (existing.pictures.length === 0 ? (
            <p className="muted">
              {existing.previewStatus === 'PENDING' ? 'A picture is being fetched from the link.' : 'No pictures yet.'}
            </p>
          ) : (
            <ul className="form-pictures">
              {existing.pictures.map((picture, index) => (
                <li key={picture.id}>
                  <button
                    type="button"
                    className="picture-button"
                    aria-expanded={large === picture.id}
                    aria-label={`Picture ${index + 1}: show it larger`}
                    onClick={() => setLarge(large === picture.id ? null : picture.id)}
                  >
                    <InspirationPicture inspirationId={existing.id} picture={picture} size="THUMBNAIL" alt="" />
                  </button>
                  {picture.preview && <span className="hint">From the link</span>}
                  {typeof asking === 'object' && asking?.picture === picture.id ? (
                    <div className="stack-tight" role="group" aria-label={`Remove picture ${index + 1}`}>
                      <p>Remove it? This cannot be undone.</p>
                      <button type="button" className="button button-danger" disabled={busy} onClick={() => removeOne(picture)}>
                        Yes, remove it
                      </button>
                      <button type="button" className="button button-quiet" disabled={busy} onClick={() => setAsking(null)}>
                        Keep it
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      className="button button-quiet"
                      aria-label={`Remove picture ${index + 1}`}
                      disabled={busy}
                      onClick={() => setAsking({ picture: picture.id })}
                    >
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>
          ))}
        {existing !== null && largePicture !== undefined && (
          <div className="picture-large stack-tight">
            <InspirationPicture
              key={largePicture.id}
              inspirationId={existing.id}
              picture={largePicture}
              size="FULL"
              ownShape
              alt={`Picture ${existing.pictures.indexOf(largePicture) + 1} of “${name}”`}
            />
            <div>
              <button type="button" className="button button-quiet" onClick={() => setLarge(null)}>
                Show it smaller
              </button>
            </div>
          </div>
        )}
        {existing === null && chosen.length > 0 && (
          <ul className="chosen-files">
            {chosen.map((file, index) => (
              <li key={`${file.name}-${index}`}>
                <span>{file.name}</span>
                <button
                  type="button"
                  className="button button-quiet"
                  aria-label={`Leave out ${file.name}`}
                  onClick={() => setChosen(chosen.filter((_, other) => other !== index))}
                >
                  Leave out
                </button>
              </li>
            ))}
          </ul>
        )}
        {picturesProblem !== null && (
          <p role="alert" className="alert">
            {picturesProblem}
          </p>
        )}
        {room > 0 ? (
          <div className="stack-tight">
            <div>
              <PictureChooser label={existing === null ? 'Choose pictures' : 'Add pictures'} disabled={busy} onFiles={takePictures} />
            </div>
            <p className="hint">
              Or drop them on this page. Up to {MOST_PICTURES} pictures, 15 MB each.
              {existing === null ? ' They are saved together with the rest.' : ''}
            </p>
          </div>
        ) : (
          <p className="hint">An inspiration holds at most {MOST_PICTURES} pictures.</p>
        )}
      </section>

      <form className="stack" onSubmit={save} noValidate>
        <Field
          label="Link"
          optional
          hint={
            existing === null
              ? 'From Instagram, TikTok, Pinterest or any page.'
              : 'After a change the planner fetches the title and picture of the new page.'
          }
          error={fieldError('url')}
        >
          {(control) => (
            <input
              {...control}
              type="url"
              name="url"
              inputMode="url"
              autoComplete="off"
              placeholder="https://"
              maxLength={2000}
              value={url}
              onChange={(event) => setUrl(event.target.value)}
            />
          )}
        </Field>

        <Field label="Board" optional error={fieldError('boardId')}>
          {(control) => (
            <select {...control} name="boardId" value={boardId} onChange={(event) => setBoardId(event.target.value)}>
              <option value="">No board</option>
              {boards.map((board) => (
                <option key={board.id} value={board.id}>
                  {board.name}
                </option>
              ))}
            </select>
          )}
        </Field>

        <Field
          label="Title"
          optional
          hint={existing !== null && existing.previewTitle !== '' ? 'Left empty, the card carries the page’s own title.' : undefined}
          error={fieldError('title')}
        >
          {(control) => (
            <input
              {...control}
              name="title"
              maxLength={200}
              placeholder={existing?.previewTitle ?? ''}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
            />
          )}
        </Field>

        <Field label="Note" optional error={fieldError('note')}>
          {(control) => (
            <textarea {...control} name="note" rows={4} maxLength={10000} value={note} onChange={(event) => setNote(event.target.value)} />
          )}
        </Field>

        <Field label="Tags" optional hint="Separate them with commas: boho, lace sleeves, outdoor." error={tagsError}>
          {(control) => (
            <input {...control} name="tags" autoCapitalize="none" value={tags} onChange={(event) => setTags(event.target.value)} />
          )}
        </Field>

        <Field label="Belongs to" optional hint="The plan item this idea is for." error={fieldError('planItemId')}>
          {(control) => (
            <select {...control} name="planItemId" value={planItemId} onChange={(event) => setPlanItemId(event.target.value)}>
              <option value="">No plan item</option>
              {planItems.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title}
                </option>
              ))}
            </select>
          )}
        </Field>

        <Field label="Vendor" optional hint="Who this idea concerns." error={fieldError('vendorId')}>
          {(control) => (
            <select {...control} name="vendorId" value={vendorId} onChange={(event) => setVendorId(event.target.value)}>
              <option value="">No vendor</option>
              {vendors.map((vendor) => (
                <option key={vendor.id} value={vendor.id}>
                  {vendor.name}
                </option>
              ))}
            </select>
          )}
        </Field>

        <div className="actions">
          <button type="submit" className="button" disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
          <Link className="button button-quiet" to={back}>
            Cancel
          </Link>
        </div>
      </form>

      {existing !== null && (
        <section className="danger-zone stack" aria-label="More">
          <h2>More</h2>
          {asking !== 'delete' ? (
            <div>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => setAsking('delete')}>
                Delete this inspiration
              </button>
            </div>
          ) : (
            <div className="stack" role="group" aria-label="Delete">
              <p>
                Delete “{name}”{existing.pictures.length > 0 ? ' and its pictures' : ''}? This cannot be undone.
              </p>
              <div className="actions">
                <button type="button" className="button button-danger" disabled={busy} onClick={remove}>
                  Yes, delete it
                </button>
                <button type="button" className="button button-quiet" disabled={busy} onClick={() => setAsking(null)}>
                  Do not delete
                </button>
              </div>
            </div>
          )}
        </section>
      )}
      {drop.dropping && (
        <p className="drop-here" aria-hidden="true">
          Drop the pictures to add them here.
        </p>
      )}
    </div>
  )
}

/** The pictures were not taken; the message is already in words for the person. */
class PicturesRefused extends Error {}
