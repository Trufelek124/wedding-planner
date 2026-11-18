import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App.tsx'
import { CAKE, DRESS, entry, inspiration, inspirationsServer, picture, pictureBytes, problem } from '../test-server.ts'
import { forgetPictures } from './pictures.ts'

function open(path: string) {
  window.history.pushState(null, '', path)
  render(<App />)
}

function query(request: Request | undefined): Record<string, string[]> {
  const result: Record<string, string[]> = {}
  new URL(request?.url ?? 'http://x').searchParams.forEach((value, name) => {
    result[name] = [...(result[name] ?? []), value]
  })
  return result
}

function photo(name: string, type = 'image/jpeg'): File {
  return new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0])], name, { type })
}

function card(name: string) {
  return within(screen.getByRole('article', { name }))
}

/** What a multipart request carried under "files": how many parts, and how large each is. */
async function sizesSent(request: Request | undefined): Promise<number[]> {
  const form = await request?.formData()
  return (form?.getAll('files') ?? []).map((part) => (part as File).size)
}

let addresses = 0
let letGo = vi.spyOn(URL, 'revokeObjectURL')

beforeEach(() => {
  addresses = 0
  // Each fetched picture gets an address of the browser's own; the tests only need to tell them apart.
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:picture-${(addresses += 1)}`)
  letGo = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
})

afterEach(() => {
  forgetPictures()
  vi.useRealTimers()
})

describe('the board of inspirations', () => {
  it('shows the picture where there is one, and the source and the note where there is none', async () => {
    const itsPicture = picture()
    const fitting = entry({ kind: 'PLAN_ITEM', title: 'Book the fitting' })
    const sleeves = inspiration({ title: 'Lace sleeves', url: 'https://example.com/lace', boardId: DRESS.id,
      tags: ['boho', 'lace'], pictures: [itsPicture, picture()], planItemId: fitting.id })
    const reel = inspiration({ url: 'https://www.instagram.com/reel/abc/', source: 'INSTAGRAM', note: 'The veil at 0:14' })
    const server = inspirationsServer([sleeves, reel], { 'GET /api/entries': Response.json([fitting]) })

    open('/inspirations')

    const withPicture = within(await screen.findByRole('article', { name: 'Lace sleeves' }))
    await waitFor(() => expect(document.querySelector('.idea img')).toHaveAttribute('src', 'blob:picture-1'))
    // The picture is asked for with the access token, in the size the board needs.
    const asked = server.requests(`GET /api/inspirations/${sleeves.id}/pictures/${itsPicture.id}`)
    expect(asked).toHaveLength(1)
    expect(asked[0]?.headers.get('Authorization')).toBe('Bearer access-1')
    expect(query(asked[0])).toEqual({ size: ['THUMBNAIL'] })
    expect(withPicture.getByText('Dress')).toBeInTheDocument()
    expect(withPicture.getByText('1 more picture')).toBeInTheDocument()
    expect(withPicture.getByText('For Book the fitting')).toBeInTheDocument()
    expect(withPicture.getByRole('button', { name: 'Show only “boho”' })).toBeInTheDocument()

    // A card opens the original, in a tab of its own that learns nothing about the planner.
    const original = withPicture.getByRole('link', { name: /^Lace sleeves/ })
    expect(original).toHaveAttribute('href', 'https://example.com/lace')
    expect(original).toHaveAttribute('target', '_blank')
    expect(original).toHaveAttribute('rel', 'noopener noreferrer')
    expect(withPicture.getByRole('link', { name: 'Change “Lace sleeves”' })).toHaveAttribute('href', `/inspirations/${sleeves.id}`)

    const withoutPicture = card('From Instagram')
    expect(withoutPicture.getAllByText('Instagram').length).toBeGreaterThan(0)
    expect(withoutPicture.getByText('The veil at 0:14')).toBeInTheDocument()
    expect(withoutPicture.queryByRole('img')).not.toBeInTheDocument()
  })

  it('names a card after the page when the person gave it no title, and after the site for a plain link', async () => {
    inspirationsServer([
      inspiration({ previewTitle: 'Peonies in June', previewStatus: 'FETCHED' }),
      inspiration({ url: 'https://www.florist.example/arch' }),
      inspiration({ url: null, source: 'PHOTO', pictures: [picture(), picture()] }),
      inspiration({ url: null, source: 'PHOTO', pictures: [picture()], boardId: DRESS.id }),
      inspiration({ url: null, source: 'PHOTO', note: 'Ask about candles' }),
    ])
    open('/inspirations')

    expect(await screen.findByRole('article', { name: 'Peonies in June' })).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'From florist.example' })).toBeInTheDocument()
    // Pictures of one's own have no original: the card leads to its form, and does not name a source.
    expect(card('Pictures').getByRole('link', { name: 'Pictures' })).not.toHaveAttribute('target')
    expect(card('A picture').getByText('Dress')).toBeInTheDocument()
    expect(card('A picture').queryByText('Photo')).not.toBeInTheDocument()
    expect(card('An idea').getByText('No picture')).toBeInTheDocument()
    expect(card('An idea').getByText('Ask about candles')).toBeInTheDocument()
  })

  it('narrows down by board and by tag, and keeps both in the address', async () => {
    const server = inspirationsServer([inspiration({ title: 'Lace sleeves', tags: ['boho', 'lace'] })])
    open('/inspirations')

    await userEvent.selectOptions(await screen.findByLabelText('Board'), 'Dress (2)')
    await waitFor(() => expect(query(server.requests('GET /api/inspirations').at(-1))).toEqual({ boardId: [DRESS.id] }))

    await userEvent.click(card('Lace sleeves').getByRole('button', { name: 'Show only “lace”' }))
    await waitFor(() =>
      expect(query(server.requests('GET /api/inspirations').at(-1))).toEqual({ boardId: [DRESS.id], tag: ['lace'] }),
    )
    expect(window.location.search).toBe(`?board=${DRESS.id}&tag=lace`)
    expect(screen.getByLabelText('Tag')).toHaveValue('lace')

    await userEvent.selectOptions(screen.getByLabelText('Board'), 'On no board')
    await userEvent.selectOptions(screen.getByLabelText('Tag'), 'Any tag')
    await waitFor(() => expect(query(server.requests('GET /api/inspirations').at(-1))).toEqual({ withoutBoard: ['true'] }))
    expect(window.location.search).toBe('?board=none')
  })

  it('opens narrowed down when the address says so', async () => {
    const server = inspirationsServer([])
    open(`/inspirations?board=${CAKE.id}&tag=naked%20cake`)

    expect(await screen.findByText('Nothing here matches. Choose another board or tag.')).toBeInTheDocument()
    expect(query(server.requests('GET /api/inspirations')[0])).toEqual({ boardId: [CAKE.id], tag: ['naked cake'] })
    expect(screen.getByLabelText('Board')).toHaveValue(CAKE.id)
    // The tag is still the choice made, although nothing carries it.
    expect(screen.getByLabelText('Tag')).toHaveValue('naked cake')
  })

  it('says so when nothing is kept yet', async () => {
    inspirationsServer([])
    open('/inspirations')

    expect(await screen.findByText('Nothing kept yet. Paste a link above, or drop a few pictures on this page.')).toBeInTheDocument()
  })

  it('is one of the pages in the top bar', async () => {
    inspirationsServer([])
    open('/')

    await userEvent.click(await screen.findByRole('link', { name: 'Inspirations' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Inspirations' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Inspirations' })).toHaveAttribute('aria-current', 'page')
  })

  it('offers to try again when the server cannot be reached', async () => {
    const server = inspirationsServer([inspiration({ title: 'Lace sleeves' })], { 'GET /api/inspirations': new TypeError('Failed to fetch') })
    open('/inspirations')

    expect(await screen.findByText('The inspirations could not be loaded. Try again.')).toBeInTheDocument()
    server.answer('GET /api/inspirations', Response.json([inspiration({ title: 'Lace sleeves' })]))
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('article', { name: 'Lace sleeves' })).toBeInTheDocument()
  })
})

describe('adding from the board', () => {
  it('saves the same link again under the same id after no answer came, and another link under another', async () => {
    const server = inspirationsServer([], {})
    open('/inspirations')
    await userEvent.type(await screen.findByLabelText('Add a link'), 'https://www.tiktok.com/@x/video/1')

    // The server may have taken it, but no answer came back: the link stays in the field.
    await userEvent.click(screen.getByRole('button', { name: 'Save link' }))
    expect(await screen.findByText('The link could not be saved. Try again.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Save link' }))
    await waitFor(() => expect(server.requests('POST /api/inspirations')).toHaveLength(2))
    await userEvent.clear(screen.getByLabelText('Add a link'))
    await userEvent.type(screen.getByLabelText('Add a link'), 'https://www.tiktok.com/@x/video/2')
    await userEvent.click(screen.getByRole('button', { name: 'Save link' }))
    await waitFor(() => expect(server.requests('POST /api/inspirations')).toHaveLength(3))

    const sent = await Promise.all(server.requests('POST /api/inspirations').map((request) => request.json() as Promise<{ id: string }>))
    expect(sent[0]?.id).toMatch(/^[0-9a-f-]{36}$/)
    expect(sent[1]?.id).toBe(sent[0]?.id)
    expect(sent[2]?.id).not.toBe(sent[0]?.id)
  })

  it('saves a pasted link onto the board that is shown', async () => {
    const saved = inspiration({ url: 'https://www.tiktok.com/@x/video/1', source: 'TIKTOK', boardId: DRESS.id })
    const server = inspirationsServer([], { 'POST /api/inspirations': Response.json(saved, { status: 201 }) })
    open(`/inspirations?board=${DRESS.id}`)

    await userEvent.type(await screen.findByLabelText('Add a link'), '  https://www.tiktok.com/@x/video/1 ')
    server.answer('GET /api/inspirations', Response.json([saved]))
    await userEvent.click(screen.getByRole('button', { name: 'Save link' }))

    expect(await screen.findByRole('article', { name: 'From TikTok' })).toBeInTheDocument()
    await expect(server.requests('POST /api/inspirations')[0]?.json()).resolves.toEqual({
      id: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
      url: 'https://www.tiktok.com/@x/video/1', boardId: DRESS.id, title: '', note: '', tags: [], planItemId: null, vendorId: null,
    })
    expect(screen.getByRole('status')).toHaveTextContent('The link was saved.')
    expect(screen.getByLabelText('Add a link')).toHaveValue('')
  })

  it('looks again until the preview of a new link has arrived', async () => {
    // The clock still runs by itself, so that everything else on the page behaves; the test only jumps ahead.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true })
    const waiting = inspiration({ url: 'https://www.florist.example/arch', previewStatus: 'PENDING' })
    const itsPicture = picture({ preview: true })
    const server = inspirationsServer([waiting], {
      [`GET /api/inspirations/${waiting.id}/pictures/${itsPicture.id}`]: pictureBytes,
    })
    open('/inspirations')

    const waitingCard = await screen.findByRole('article', { name: 'From florist.example' })
    expect(within(waitingCard).getByText('Fetching a preview…')).toBeInTheDocument()
    expect(server.requests('GET /api/inspirations')).toHaveLength(1)

    server.answer('GET /api/inspirations',
      Response.json([{ ...waiting, previewStatus: 'FETCHED', previewTitle: 'A flower arch', pictures: [itsPicture] }]))
    await act(() => vi.advanceTimersByTimeAsync(3000))

    expect(await screen.findByRole('article', { name: 'A flower arch' })).toBeInTheDocument()
    expect(server.requests('GET /api/inspirations')).toHaveLength(2)
    // Nothing is waited for any more, so the board stops asking.
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(server.requests('GET /api/inspirations')).toHaveLength(2)
  })

  it('says next to the field what is wrong with a link', async () => {
    const server = inspirationsServer([], {
      'POST /api/inspirations': problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [{ field: 'url', message: 'Enter a link that starts with http:// or https://.' }],
      }),
    })
    open('/inspirations')

    await userEvent.click(await screen.findByRole('button', { name: 'Save link' }))
    expect(screen.getByLabelText('Add a link')).toHaveAccessibleDescription(/Paste a link first\./)
    expect(server.calls).not.toContain('POST /api/inspirations')

    await userEvent.type(screen.getByLabelText('Add a link'), 'ftp://example.com/x')
    await userEvent.click(screen.getByRole('button', { name: 'Save link' }))

    await waitFor(() =>
      expect(screen.getByLabelText('Add a link')).toHaveAccessibleDescription(/Enter a link that starts with http:\/\/ or https:\/\/\./),
    )
    expect(screen.getByLabelText('Add a link')).toBeInvalid()
    expect(screen.getByLabelText('Add a link')).toHaveValue('ftp://example.com/x')
  })

  it('keeps chosen pictures as one new idea', async () => {
    const created = inspiration({ url: null, source: 'PHOTO' })
    const server = inspirationsServer([], {
      'POST /api/inspirations': Response.json(created, { status: 201 }),
      [`POST /api/inspirations/${created.id}/pictures`]: Response.json({ ...created, pictures: [picture(), picture()] }),
    })
    open('/inspirations')

    await userEvent.upload(await screen.findByLabelText('Add pictures'), [photo('arch.jpg'), photo('peonies.png', 'image/png')])

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('2 pictures were saved as one idea.'))
    await expect(server.requests('POST /api/inspirations')[0]?.json()).resolves.toEqual({
      id: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
      url: null, boardId: null, title: '', note: '', tags: [], planItemId: null, vendorId: null,
    })
    const upload = server.requests(`POST /api/inspirations/${created.id}/pictures`)[0]
    expect(upload?.headers.get('Content-Type')).toMatch(/^multipart\/form-data; boundary=/)
    await expect(sizesSent(upload)).resolves.toEqual([4, 4])
    // The board is asked again, to show the new card.
    expect(server.requests('GET /api/inspirations').length).toBeGreaterThan(1)
  })

  it('keeps pictures dropped on the page', async () => {
    const created = inspiration({ url: null, source: 'PHOTO' })
    const server = inspirationsServer([], {
      'POST /api/inspirations': Response.json(created, { status: 201 }),
      [`POST /api/inspirations/${created.id}/pictures`]: Response.json({ ...created, pictures: [picture()] }),
    })
    open('/inspirations')
    const page = (await screen.findByRole('heading', { name: 'Inspirations' })).closest('.ideas-page') as HTMLElement

    fireEvent.dragOver(page, { dataTransfer: { types: ['Files'], files: [] } })
    expect(screen.getByText('Drop the pictures to keep them as a new idea.')).toBeInTheDocument()
    fireEvent.drop(page, { dataTransfer: { types: ['Files'], files: [photo('arch.jpg')] } })

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('The picture was saved.'))
    expect(screen.queryByText('Drop the pictures to keep them as a new idea.')).not.toBeInTheDocument()
    await expect(sizesSent(server.requests(`POST /api/inspirations/${created.id}/pictures`)[0])).resolves.toEqual([4])
  })

  it('takes the new idea back when the server does not take its pictures', async () => {
    const created = inspiration({ url: null, source: 'PHOTO' })
    const server = inspirationsServer([], {
      'POST /api/inspirations': Response.json(created, { status: 201 }),
      [`POST /api/inspirations/${created.id}/pictures`]: problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [{ field: 'files', message: '“notes.jpg” could not be read as a picture.' }],
      }),
      [`DELETE /api/inspirations/${created.id}`]: new Response(null, { status: 204 }),
    })
    open('/inspirations')

    await userEvent.upload(await screen.findByLabelText('Add pictures'), photo('notes.jpg'))

    expect(await screen.findByRole('alert')).toHaveTextContent('“notes.jpg” could not be read as a picture.')
    expect(server.calls).toContain(`DELETE /api/inspirations/${created.id}`)
  })

  it('says so before sending anything when a file is too large, is no picture, or there are too many', async () => {
    const server = inspirationsServer([])
    const user = userEvent.setup({ applyAccept: false })
    open('/inspirations')
    const chooser = await screen.findByLabelText('Add pictures')

    const huge = photo('wall.jpg')
    Object.defineProperty(huge, 'size', { value: 15 * 1024 * 1024 + 1 })
    await user.upload(chooser, huge)
    expect(screen.getByRole('alert')).toHaveTextContent('“wall.jpg” is larger than 15 MB. Choose a smaller picture.')

    await user.upload(chooser, new File(['hello'], 'list.txt', { type: 'text/plain' }))
    expect(screen.getByRole('alert')).toHaveTextContent('“list.txt” is not a picture.')

    await user.upload(chooser, Array.from({ length: 11 }, (_, index) => photo(`${index}.jpg`)))
    expect(screen.getByRole('alert')).toHaveTextContent('An inspiration holds at most 10 pictures.')

    expect(server.calls).not.toContain('POST /api/inspirations')
  })
})

describe('the form of an inspiration', () => {
  it('adds a link with a board, a note, tags and the plan item it is for', async () => {
    const flowers = entry({ kind: 'PLAN_ITEM', title: 'Choose the flowers' })
    const server = inspirationsServer([], {
      'GET /api/entries': Response.json([flowers]),
      'POST /api/inspirations': Response.json(inspiration(), { status: 201 }),
    })
    open('/inspirations/new')

    await userEvent.type(await screen.findByLabelText(/^Link/), 'https://www.pinterest.com/pin/1/')
    await userEvent.selectOptions(screen.getByLabelText(/^Board/), 'Cake')
    await userEvent.type(screen.getByLabelText(/^Title/), 'Three tiers')
    await userEvent.type(screen.getByLabelText(/^Note/), 'Ask the baker about figs')
    await userEvent.type(screen.getByLabelText(/^Tags/), 'naked cake, figs,')
    await userEvent.selectOptions(screen.getByLabelText(/^Belongs to/), 'Choose the flowers')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Inspirations' })).toBeInTheDocument()
    await expect(server.requests('POST /api/inspirations')[0]?.json()).resolves.toEqual({
      id: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
      url: 'https://www.pinterest.com/pin/1/', boardId: CAKE.id, title: 'Three tiers', note: 'Ask the baker about figs',
      tags: ['naked cake', 'figs'], planItemId: flowers.id, vendorId: null,
    })
    expect(query(server.requests('GET /api/entries')[0])).toEqual({ kind: ['PLAN_ITEM'], sort: ['TITLE'] })
  })

  it('adds pictures with a board, a note and tags, in one go', async () => {
    const created = inspiration({ url: null, source: 'PHOTO' })
    const server = inspirationsServer([], {
      'POST /api/inspirations': Response.json(created, { status: 201 }),
      [`POST /api/inspirations/${created.id}/pictures`]: Response.json({ ...created, pictures: [picture()] }),
    })
    open('/inspirations/new')

    await userEvent.upload(await screen.findByLabelText('Choose pictures'), [photo('arch.jpg'), photo('table.jpg')])
    expect(screen.getByText('arch.jpg')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Leave out table.jpg' }))
    expect(screen.queryByText('table.jpg')).not.toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText(/^Board/), 'Dress')
    await userEvent.type(screen.getByLabelText(/^Note/), 'From the fitting')
    await userEvent.type(screen.getByLabelText(/^Tags/), 'lace')
    // Nothing is sent before the person saves.
    expect(server.calls).not.toContain('POST /api/inspirations')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Inspirations' })).toBeInTheDocument()
    await expect(server.requests('POST /api/inspirations')[0]?.json()).resolves.toEqual({
      id: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
      url: null, boardId: DRESS.id, title: '', note: 'From the fitting', tags: ['lace'], planItemId: null, vendorId: null,
    })
    await expect(sizesSent(server.requests(`POST /api/inspirations/${created.id}/pictures`)[0])).resolves.toEqual([4])
  })

  it('saves nothing when the pictures of a new idea are refused, and keeps what was typed', async () => {
    const created = inspiration({ url: null, source: 'PHOTO' })
    const server = inspirationsServer([], {
      'POST /api/inspirations': Response.json(created, { status: 201 }),
      [`POST /api/inspirations/${created.id}/pictures`]: problem(413, 'upload-too-large', 'Too large'),
      [`DELETE /api/inspirations/${created.id}`]: new Response(null, { status: 204 }),
    })
    open('/inspirations/new')

    await userEvent.upload(await screen.findByLabelText('Choose pictures'), photo('arch.jpg'))
    await userEvent.type(screen.getByLabelText(/^Note/), 'From the fitting')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('That is more than the planner takes at once. Add fewer or smaller pictures.')
    expect(server.calls).toContain(`DELETE /api/inspirations/${created.id}`)
    expect(screen.getByLabelText(/^Note/)).toHaveValue('From the fitting')
    expect(screen.getByText('arch.jpg')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('asks for a link or a picture', async () => {
    const server = inspirationsServer([])
    open('/inspirations/new')

    await userEvent.type(await screen.findByLabelText(/^Note/), 'Just a thought')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Paste a link or choose at least one picture.')
    expect(server.calls).not.toContain('POST /api/inspirations')
  })

  it('starts on the board that was being looked at, and returns to it', async () => {
    const server = inspirationsServer([], { 'POST /api/inspirations': Response.json(inspiration(), { status: 201 }) })
    open(`/inspirations?board=${DRESS.id}&tag=lace`)

    await userEvent.click(await screen.findByRole('link', { name: 'Add an inspiration' }))
    expect(await screen.findByLabelText(/^Board/)).toHaveValue(DRESS.id)
    await userEvent.type(screen.getByLabelText(/^Link/), 'https://example.com/x')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Inspirations' })).toBeInTheDocument()
    expect(window.location.search).toBe(`?board=${DRESS.id}&tag=lace`)
    expect(server.calls).toContain('POST /api/inspirations')
  })

  it('changes the board, the note, the tags and the plan item, based on the version it loaded', async () => {
    const flowers = entry({ kind: 'PLAN_ITEM', title: 'Choose the flowers' })
    const arch = inspiration({ url: 'https://www.florist.example/arch', previewTitle: 'A flower arch', previewStatus: 'FETCHED',
      boardId: DRESS.id, note: 'Too big?', tags: ['arch'], version: 3 })
    const server = inspirationsServer([arch], {
      'GET /api/entries': Response.json([flowers]),
      [`GET /api/inspirations/${arch.id}`]: Response.json(arch),
      [`PUT /api/inspirations/${arch.id}`]: Response.json({ ...arch, version: 4 }),
    })
    open(`/inspirations/${arch.id}`)

    expect(await screen.findByRole('link', { name: /Open the original/ })).toHaveAttribute('href', 'https://www.florist.example/arch')
    // The page's own title is offered, not filled in.
    expect(screen.getByLabelText(/^Title/)).toHaveValue('')
    expect(screen.getByLabelText(/^Title/)).toHaveAttribute('placeholder', 'A flower arch')
    await userEvent.selectOptions(screen.getByLabelText(/^Board/), 'No board')
    await userEvent.clear(screen.getByLabelText(/^Note/))
    await userEvent.type(screen.getByLabelText(/^Note/), 'For the garden ceremony')
    await userEvent.type(screen.getByLabelText(/^Tags/), ', outdoor')
    await userEvent.selectOptions(screen.getByLabelText(/^Belongs to/), 'Choose the flowers')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Inspirations' })).toBeInTheDocument()
    await expect(server.requests(`PUT /api/inspirations/${arch.id}`)[0]?.json()).resolves.toEqual({
      version: 3, url: 'https://www.florist.example/arch', boardId: null, title: '', note: 'For the garden ceremony',
      tags: ['arch', 'outdoor'], planItemId: flowers.id, vendorId: null,
    })
  })

  it('says the vendor of an inspiration again as it is', async () => {
    const vendorId = 'aaaaaaaa-0000-4000-8000-000000000001'
    const arch = inspiration({ note: 'Too big?', vendorId })
    const server = inspirationsServer([arch], {
      [`GET /api/inspirations/${arch.id}`]: Response.json(arch),
      [`PUT /api/inspirations/${arch.id}`]: Response.json({ ...arch, version: 1 }),
    })
    open(`/inspirations/${arch.id}`)

    await userEvent.type(await screen.findByLabelText(/^Note/), ' Maybe.')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT /api/inspirations/${arch.id}`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/inspirations/${arch.id}`)[0]?.json()).resolves.toMatchObject({ note: 'Too big? Maybe.', vendorId })
  })

  it('offers the latest version when someone else changed it meanwhile, and overwrites nothing', async () => {
    const arch = inspiration({ note: 'Too big?', version: 3 })
    const server = inspirationsServer([arch], {
      [`GET /api/inspirations/${arch.id}`]: Response.json(arch),
      [`PUT /api/inspirations/${arch.id}`]: problem(409, 'changed-meanwhile', 'Changed meanwhile'),
    })
    open(`/inspirations/${arch.id}`)

    await userEvent.type(await screen.findByLabelText(/^Note/), ' Maybe.')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Someone else changed this in the meantime, so your change was not saved.')).toBeInTheDocument()

    server.answer(`GET /api/inspirations/${arch.id}`, Response.json({ ...arch, note: 'Piotr likes it', version: 4 }))
    await userEvent.click(screen.getByRole('button', { name: 'Load the latest version' }))

    await waitFor(() => expect(screen.getByLabelText(/^Note/)).toHaveValue('Piotr likes it'))
    expect(server.requests(`PUT /api/inspirations/${arch.id}`)).toHaveLength(1)
  })

  it('shows what the server says about a field next to it', async () => {
    const arch = inspiration()
    inspirationsServer([arch], {
      [`GET /api/inspirations/${arch.id}`]: Response.json(arch),
      [`PUT /api/inspirations/${arch.id}`]: problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [
          { field: 'url', message: 'Enter a link that starts with http:// or https://.' },
          { field: 'tags[1]', message: 'Use at most 40 characters for a tag.' },
        ],
      }),
    })
    open(`/inspirations/${arch.id}`)

    await userEvent.click(await screen.findByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByLabelText(/^Link/)).toBeInvalid())
    expect(screen.getByLabelText(/^Link/)).toHaveAccessibleDescription(/Enter a link that starts with/)
    expect(screen.getByLabelText(/^Tags/)).toHaveAccessibleDescription(/Use at most 40 characters for a tag\./)
  })

  it('attaches a picture later to an idea that came without one', async () => {
    const reel = inspiration({ url: 'https://www.instagram.com/reel/abc/', source: 'INSTAGRAM', note: 'The veil' })
    const screenshot = picture()
    const server = inspirationsServer([reel], {
      [`GET /api/inspirations/${reel.id}`]: Response.json(reel),
      [`POST /api/inspirations/${reel.id}/pictures`]: Response.json({ ...reel, pictures: [screenshot] }),
      [`GET /api/inspirations/${reel.id}/pictures/${screenshot.id}`]: pictureBytes,
    })
    open(`/inspirations/${reel.id}`)

    expect(await screen.findByText('No pictures yet.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/^Note/), ' at 0:14')
    await userEvent.upload(screen.getByLabelText('Add pictures'), photo('screenshot.png', 'image/png'))

    expect(await screen.findByRole('button', { name: 'Picture 1: show it larger' })).toBeInTheDocument()
    await expect(sizesSent(server.requests(`POST /api/inspirations/${reel.id}/pictures`)[0])).resolves.toEqual([4])
    expect(screen.queryByText('No pictures yet.')).not.toBeInTheDocument()
    // What was being typed is still there.
    expect(screen.getByLabelText(/^Note/)).toHaveValue('The veil at 0:14')
  })

  it('says why pictures were not taken, and keeps the form as it was', async () => {
    const reel = inspiration({ pictures: [picture()] })
    inspirationsServer([reel], {
      [`GET /api/inspirations/${reel.id}`]: Response.json(reel),
      [`POST /api/inspirations/${reel.id}/pictures`]: problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [{ field: 'files', message: '“x.jpg” has more pixels than the planner takes.' }],
      }),
    })
    open(`/inspirations/${reel.id}`)

    await userEvent.upload(await screen.findByLabelText('Add pictures'), photo('x.jpg'))

    expect(await screen.findByRole('alert')).toHaveTextContent('“x.jpg” has more pixels than the planner takes.')
    expect(screen.getByRole('button', { name: 'Picture 1: show it larger' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('shows a picture larger, fetched with the access token', async () => {
    const first = picture()
    const dress = inspiration({ title: 'Lace sleeves', pictures: [first, picture({ preview: true })] })
    const server = inspirationsServer([dress], { [`GET /api/inspirations/${dress.id}`]: Response.json(dress) })
    open(`/inspirations/${dress.id}`)

    expect(await screen.findByText('From the link')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Picture 1: show it larger' }))

    expect(await screen.findByRole('img', { name: 'Picture 1 of “Lace sleeves”' })).toBeInTheDocument()
    const full = server.requests(`GET /api/inspirations/${dress.id}/pictures/${first.id}`).find((request) => query(request).size?.[0] === 'FULL')
    expect(full?.headers.get('Authorization')).toBe('Bearer access-1')
    expect(screen.getByRole('button', { name: 'Picture 1: show it larger' })).toHaveAttribute('aria-expanded', 'true')

    await userEvent.click(screen.getByRole('button', { name: 'Show it smaller' }))
    expect(screen.queryByRole('img', { name: 'Picture 1 of “Lace sleeves”' })).not.toBeInTheDocument()
  })

  it('removes a picture only after asking', async () => {
    const first = picture()
    const second = picture()
    const dress = inspiration({ title: 'Lace sleeves', pictures: [first, second] })
    const server = inspirationsServer([dress], {
      [`GET /api/inspirations/${dress.id}`]: Response.json(dress),
      [`DELETE /api/inspirations/${dress.id}/pictures/${first.id}`]: Response.json({ ...dress, pictures: [second] }),
    })
    open(`/inspirations/${dress.id}`)

    await userEvent.click(await screen.findByRole('button', { name: 'Remove picture 1' }))
    expect(server.calls).not.toContain(`DELETE /api/inspirations/${dress.id}/pictures/${first.id}`)
    await userEvent.click(within(screen.getByRole('group', { name: 'Remove picture 1' })).getByRole('button', { name: 'Keep it' }))
    expect(screen.queryByRole('group', { name: 'Remove picture 1' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Remove picture 1' }))
    await userEvent.click(screen.getByRole('button', { name: 'Yes, remove it' }))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Picture 2: show it larger' })).not.toBeInTheDocument())
    expect(server.calls).toContain(`DELETE /api/inspirations/${dress.id}/pictures/${first.id}`)
    expect(screen.getByRole('button', { name: 'Picture 1: show it larger' })).toBeInTheDocument()
  })

  it('offers no more pictures once there are ten', async () => {
    const full = inspiration({ pictures: Array.from({ length: 10 }, () => picture()) })
    inspirationsServer([full], { [`GET /api/inspirations/${full.id}`]: Response.json(full) })
    open(`/inspirations/${full.id}`)

    expect(await screen.findByText('An inspiration holds at most 10 pictures.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Add pictures')).not.toBeInTheDocument()
  })

  it('deletes an inspiration only after asking', async () => {
    const dress = inspiration({ title: 'Lace sleeves', pictures: [picture()] })
    const server = inspirationsServer([dress], {
      [`GET /api/inspirations/${dress.id}`]: Response.json(dress),
      [`DELETE /api/inspirations/${dress.id}`]: new Response(null, { status: 204 }),
    })
    open(`/inspirations/${dress.id}`)

    await userEvent.click(await screen.findByRole('button', { name: 'Delete this inspiration' }))
    expect(screen.getByText('Delete “Lace sleeves” and its pictures? This cannot be undone.')).toBeInTheDocument()
    expect(server.calls).not.toContain(`DELETE /api/inspirations/${dress.id}`)
    await userEvent.click(screen.getByRole('button', { name: 'Yes, delete it' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Inspirations' })).toBeInTheDocument()
    expect(server.calls).toContain(`DELETE /api/inspirations/${dress.id}`)
  })

  it('says so when the inspiration is not there any more', async () => {
    const gone = inspiration()
    inspirationsServer([], { [`GET /api/inspirations/${gone.id}`]: problem(404, 'not-found', 'Not found') })
    open(`/inspirations/${gone.id}`)

    expect(await screen.findByText(/This could not be loaded\. It may have been deleted\./)).toBeInTheDocument()
  })
})

describe('the boards', () => {
  it('adds a board, and says so when the name is taken', async () => {
    const server = inspirationsServer([], {
      'POST /api/boards': problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [{ field: 'name', message: 'There is a board of that name already.' }],
      }),
    })
    open('/inspirations/boards')

    await userEvent.type(await screen.findByLabelText('New board'), 'Dress')
    await userEvent.click(screen.getByRole('button', { name: 'Add board' }))
    await waitFor(() => expect(screen.getByLabelText('New board')).toHaveAccessibleDescription('There is a board of that name already.'))

    server.answer('POST /api/boards', Response.json({ id: 'b3', name: 'Rings', inspirationCount: 0 }, { status: 201 }))
    server.answer('GET /api/boards', Response.json([CAKE, DRESS, { id: 'b3', name: 'Rings', inspirationCount: 0 }]))
    await userEvent.clear(screen.getByLabelText('New board'))
    await userEvent.type(screen.getByLabelText('New board'), ' Rings ')
    await userEvent.click(screen.getByRole('button', { name: 'Add board' }))

    expect(await screen.findByText('Rings')).toBeInTheDocument()
    await expect(server.requests('POST /api/boards')[1]?.json()).resolves.toEqual({ name: 'Rings' })
    expect(screen.getByRole('status')).toHaveTextContent('“Rings” was added.')
    expect(screen.getByLabelText('New board')).toHaveValue('')
  })

  it('renames a board in its row, with the keyboard following along', async () => {
    const server = inspirationsServer([], {
      [`PUT /api/boards/${CAKE.id}`]: Response.json({ ...CAKE, name: 'Cake and sweets' }),
    })
    open('/inspirations/boards')

    await userEvent.click(await screen.findByRole('button', { name: 'Rename “Cake”' }))
    const name = screen.getByLabelText('New name for “Cake”')
    expect(name).toHaveFocus()
    expect(name).toHaveValue('Cake')
    server.answer('GET /api/boards', Response.json([{ ...CAKE, name: 'Cake and sweets' }, DRESS]))
    await userEvent.type(name, ' and sweets{Enter}')

    const renamed = await screen.findByRole('button', { name: 'Rename “Cake and sweets”' })
    await expect(server.requests(`PUT /api/boards/${CAKE.id}`)[0]?.json()).resolves.toEqual({ name: 'Cake and sweets' })
    expect(renamed).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent('“Cake” is now “Cake and sweets”.')
  })

  it('leaves a board as it was when renaming is cancelled', async () => {
    const server = inspirationsServer([])
    open('/inspirations/boards')

    await userEvent.click(await screen.findByRole('button', { name: 'Rename “Dress”' }))
    await userEvent.type(screen.getByLabelText('New name for “Dress”'), 'es')
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.getByRole('button', { name: 'Rename “Dress”' })).toHaveFocus()
    expect(server.calls.filter((call) => call.startsWith('PUT'))).toEqual([])
  })

  it('asks what happens to the inspirations of a board that has some', async () => {
    const server = inspirationsServer([], { [`DELETE /api/boards/${DRESS.id}`]: new Response(null, { status: 204 }) })
    open('/inspirations/boards')

    await userEvent.click(await screen.findByRole('button', { name: 'Remove “Dress”' }))
    const question = screen.getByRole('group', { name: 'Remove “Dress”' })
    expect(question).toHaveFocus()
    expect(within(question).getByText('“Dress” has 2 inspirations. What should happen to them?')).toBeInTheDocument()
    expect(server.calls).not.toContain(`DELETE /api/boards/${DRESS.id}`)

    server.answer('GET /api/boards', Response.json([CAKE]))
    await userEvent.click(within(question).getByRole('button', { name: 'Remove the board, keep the inspirations' }))

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Rename “Dress”' })).not.toBeInTheDocument())
    expect(query(server.requests(`DELETE /api/boards/${DRESS.id}`)[0])).toEqual({ inspirations: ['KEEP'] })
    expect(screen.getByRole('status')).toHaveTextContent('“Dress” was removed.')
    expect(screen.getByRole('status')).toHaveFocus()
  })

  it('speaks of one inspiration as one', async () => {
    const server = inspirationsServer([], {
      'GET /api/boards': Response.json([{ ...CAKE, inspirationCount: 1 }]),
      [`DELETE /api/boards/${CAKE.id}`]: new Response(null, { status: 204 }),
    })
    open('/inspirations/boards')

    expect(await screen.findByText('1 inspiration')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Remove “Cake”' }))
    expect(screen.getByText('“Cake” has 1 inspiration. What should happen to it?')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Remove the board and the inspiration' }))

    await waitFor(() => expect(query(server.requests(`DELETE /api/boards/${CAKE.id}`)[0])).toEqual({ inspirations: ['DELETE'] }))
  })

  it('removes an empty board after asking, and asks again if it is not empty after all', async () => {
    const server = inspirationsServer([], {
      [`DELETE /api/boards/${CAKE.id}`]: problem(409, 'board-has-inspirations', 'The board has inspirations'),
    })
    open('/inspirations/boards')

    await userEvent.click(await screen.findByRole('button', { name: 'Remove “Cake”' }))
    await userEvent.click(screen.getByRole('button', { name: 'Do not remove' }))
    expect(screen.getByRole('button', { name: 'Remove “Cake”' })).toHaveFocus()

    await userEvent.click(screen.getByRole('button', { name: 'Remove “Cake”' }))
    expect(screen.getByText('Remove “Cake”? Nothing is on it.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Yes, remove it' }))
    // Piotr saved something onto it in the meantime.
    expect(await screen.findByText('“Cake” has inspirations by now. What should happen to them?')).toBeInTheDocument()
    expect(query(server.requests(`DELETE /api/boards/${CAKE.id}`)[0])).toEqual({})

    server.answer(`DELETE /api/boards/${CAKE.id}`, new Response(null, { status: 204 }))
    server.answer('GET /api/boards', Response.json([DRESS]))
    await userEvent.click(screen.getByRole('button', { name: 'Remove the board and the inspirations' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('“Cake” was removed.'))
    expect(query(server.requests(`DELETE /api/boards/${CAKE.id}`)[1])).toEqual({ inspirations: ['DELETE'] })
  })

  it('is reached from the board and leads back to it as it was', async () => {
    inspirationsServer([])
    open(`/inspirations?board=${DRESS.id}`)

    await userEvent.click(await screen.findByRole('link', { name: 'Edit the boards' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Boards' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('link', { name: 'Back to the inspirations' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Inspirations' })).toBeInTheDocument()
    expect(window.location.search).toBe(`?board=${DRESS.id}`)
  })
})

describe('the pictures in the page', () => {
  it('fetches a picture once, however often it is shown', async () => {
    const itsPicture = picture()
    const dress = inspiration({ title: 'Lace sleeves', pictures: [itsPicture] })
    const server = inspirationsServer([dress], { [`GET /api/inspirations/${dress.id}`]: Response.json(dress) })
    open('/inspirations')

    await waitFor(() => expect(document.querySelector('.idea img')).not.toBeNull())
    await userEvent.click(screen.getByRole('link', { name: 'Change “Lace sleeves”' }))
    await waitFor(() => expect(document.querySelector('.picture-button img')).not.toBeNull())

    expect(server.requests(`GET /api/inspirations/${dress.id}/pictures/${itsPicture.id}`)).toHaveLength(1)
  })

  it('keeps only the last few full-size pictures in memory', async () => {
    const pictures = Array.from({ length: 6 }, () => picture())
    const dress = inspiration({ title: 'Lace sleeves', pictures })
    inspirationsServer([dress], { [`GET /api/inspirations/${dress.id}`]: Response.json(dress) })
    open(`/inspirations/${dress.id}`)
    await screen.findByRole('button', { name: 'Picture 6: show it larger' })
    await waitFor(() => expect(document.querySelectorAll('.picture-button img')).toHaveLength(6))

    for (let number = 1; number <= 6; number += 1) {
      await userEvent.click(screen.getByRole('button', { name: `Picture ${number}: show it larger` }))
      await screen.findByRole('img', { name: `Picture ${number} of “Lace sleeves”` })
    }

    // Six small ones and six large ones were fetched; the two large ones looked at first were let go of again.
    expect(letGo.mock.calls.map(([address]) => address)).toEqual(['blob:picture-7', 'blob:picture-8'])
  })

  it('says so when a picture cannot be loaded', async () => {
    const itsPicture = picture()
    const dress = inspiration({ title: 'Lace sleeves', pictures: [itsPicture] })
    inspirationsServer([dress], {
      [`GET /api/inspirations/${dress.id}/pictures/${itsPicture.id}`]: problem(404, 'not-found', 'Not found'),
    })
    open('/inspirations')

    expect(await screen.findByText('This picture could not be loaded.')).toBeInTheDocument()
  })

  it('lets go of every picture when the owner signs out', async () => {
    const dress = inspiration({ title: 'Lace sleeves', pictures: [picture()] })
    inspirationsServer([dress], { 'POST /api/auth/sign-out': new Response(null, { status: 204 }) })
    open('/inspirations')
    await waitFor(() => expect(document.querySelector('.idea img')).toHaveAttribute('src', 'blob:picture-1'))

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() => expect(letGo).toHaveBeenCalledWith('blob:picture-1'))
  })
})
