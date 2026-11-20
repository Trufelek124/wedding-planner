import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.tsx'
import type { Schemas } from '../api/client.ts'
import { entry, planningServer, problem, type FakeServer, type Handler } from '../test-server.ts'

type Report = Schemas['TaskFileReport']
type Row = Schemas['TaskFileRow']

const CHECK = 'POST /api/tasks/file/check'
const APPLY = 'POST /api/tasks/file'

function open(path = '/tasks') {
  window.history.pushState(null, '', path)
  render(<App />)
}

function row(overrides: Partial<Row> & Pick<Row, 'row'>): Row {
  return { key: `key-${overrides.row}`, title: `Task ${overrides.row}`, outcome: 'NEW', changes: [], problems: [], ...overrides }
}

function report(overrides: Partial<Report> = {}): Report {
  const rows = overrides.rows ?? []
  const count = (outcome: Row['outcome']) => rows.filter((one) => one.outcome === outcome).length
  return {
    applied: false,
    canBeApplied: true,
    added: count('NEW'),
    changed: count('CHANGED'),
    unchanged: count('UNCHANGED'),
    refused: count('REFUSED'),
    newCategories: [],
    problems: [],
    rows,
    ...overrides,
  }
}

const FILE_TEXT = '{"format":"wedding-planner-tasks","version":1,\n"tasks":[{"key":"a \\"b\\"","title":"Zażółć gęślą jaźń 🎉"}]}\n'

function file(text = FILE_TEXT, name = 'tasks.json') {
  return new File([text], name, { type: 'application/json' })
}

/** The list of tasks, with a file chosen and checked: the server answers the check with `answer`. */
async function choose(server: FakeServer | null, answer: Handler | null, chosen: File = file()) {
  if (server !== null && answer !== null) {
    server.answer(CHECK, answer)
  }
  await userEvent.upload(await screen.findByLabelText('Choose a file'), chosen)
}

const part = () => screen.getByRole('region', { name: 'Upload tasks' })

describe('Upload tasks', () => {
  it('is at the bottom of the list with its two links, also when the list is empty or the filters show nothing', async () => {
    planningServer([])
    open('/tasks?status=all&who=nobody')

    const section = await screen.findByRole('region', { name: 'Upload tasks' })
    expect(within(section).getByText(/Tasks from a file are added/)).toHaveTextContent('Nothing is ever removed.')
    const format = within(section).getByRole('link', { name: 'The format of the file' })
    expect(format).toHaveAttribute('href', '/formats/wedding-planner-tasks-1.schema.json')
    expect(format).toHaveAttribute('target', '_blank')
    const example = within(section).getByRole('link', { name: 'An example' })
    expect(example).toHaveAttribute('href', '/formats/wedding-planner-tasks-1.example.json')
    expect(example).toHaveAttribute('download')
    expect(screen.getByLabelText('Choose a file')).toHaveAttribute('type', 'file')
  })

  it('is below a list that has tasks', async () => {
    planningServer([entry({ title: 'Order the rings' })])
    open()

    const task = await screen.findByText('Order the rings')
    const section = screen.getByRole('region', { name: 'Upload tasks' })
    expect(task.compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('checks a chosen file at once, sends its text unchanged, and applies nothing', async () => {
    const server = planningServer([], { [CHECK]: Response.json(report({ rows: [row({ row: 1 })] })) })
    open()

    await choose(null, null)

    expect(await screen.findByText('The file has 1 new task.')).toBeInTheDocument()
    expect(server.requests(CHECK)).toHaveLength(1)
    const sent = server.requests(CHECK)[0]
    // Not a quoted JSON string: the very text of the file, byte for byte.
    expect(await sent?.text()).toBe(FILE_TEXT)
    expect(sent?.headers.get('Content-Type')).toBe('application/json')
    expect(sent?.headers.get('Authorization')).toMatch(/^Bearer /)
    expect(server.calls).not.toContain(APPLY)
    expect(screen.getByText('File: tasks.json')).toBeInTheDocument()
  })

  it('says that it is checking while the answer is on its way', async () => {
    let answer: (response: Response) => void = () => undefined
    planningServer([], { [CHECK]: () => new Promise<Response>((resolve) => (answer = resolve)) })
    open()

    await choose(null, null)
    expect(await screen.findByText('Checking “tasks.json”…')).toBeInTheDocument()
    expect(screen.getByLabelText('Choose a file')).toBeDisabled()

    answer(Response.json(report({ rows: [row({ row: 1 })] })))
    expect(await screen.findByText('The file has 1 new task.')).toBeInTheDocument()
    expect(screen.getByLabelText('Choose a file')).toBeEnabled()
  })

  it('says what a file of only new tasks would do, by title, with the button', async () => {
    const server = planningServer([])
    open()

    await choose(server, Response.json(report({
      newCategories: ['Honeymoon', 'Music'],
      rows: [row({ row: 1, title: 'Book the flights' }), row({ row: 2, title: 'Book the hotel' }), row({ row: 3, title: 'Pick the playlist' })],
    })))

    expect(await screen.findByText('The file has 3 new tasks.')).toBeInTheDocument()
    expect(screen.getByText('These categories would be added: Honeymoon, Music.')).toBeInTheDocument()
    const added = within(part()).getByRole('heading', { name: 'New tasks' }).parentElement as HTMLElement
    expect(within(added).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Book the flights', 'Book the hotel', 'Pick the playlist',
    ])
    expect(within(part()).queryByRole('heading', { name: 'Tasks that would change' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add 3 tasks' })).toBeInTheDocument()
  })

  it('says a single new category in the singular', async () => {
    const server = planningServer([])
    open()

    await choose(server, Response.json(report({ newCategories: ['Honeymoon'], rows: [row({ row: 1 })] })))

    expect(await screen.findByText('This category would be added: Honeymoon.')).toBeInTheDocument()
  })

  it('says every kind of change in the page\'s own words', async () => {
    const server = planningServer([])
    open()

    await choose(server, Response.json(report({
      rows: [
        row({ row: 1, title: 'Pay the venue', outcome: 'CHANGED', changes: [
          { field: 'dueDate', from: '2027-01-15', to: '2027-02-01' },
          { field: 'status', from: 'NOT_STARTED', to: 'DONE' },
          { field: 'priority', from: null, to: 'HIGH' },
          { field: 'category', from: 'Venue', to: 'Flowers and decor' },
          { field: 'vendor', from: null, to: 'Riverside Barn' },
          { field: 'amount', from: '1200000', to: '250050' },
          { field: 'paymentKind', from: 'DEPOSIT', to: 'FINAL' },
          { field: 'paidOn', from: null, to: '2026-11-10' },
        ] }),
        row({ row: 2, title: 'Order the rings', outcome: 'CHANGED', changes: [
          { field: 'priority', from: 'LOW', to: null },
          { field: 'notes', from: 'By transfer', to: '' },
          { field: 'dueDate', from: '2027-03-01', to: null },
          { field: 'category', from: 'Rings', to: null },
          { field: 'vendor', from: 'Jeweller', to: null },
          { field: 'amount', from: '5000', to: null },
          { field: 'title', from: 'Rings', to: 'Order the rings' },
        ] }),
        row({ row: 3, title: 'Send thanks', outcome: 'UNCHANGED' }),
      ],
    })))

    expect(await screen.findByText('The file has 2 tasks that would change and 1 task that stays as it is.')).toBeInTheDocument()
    for (const line of [
      'Due: 15 Jan 2027 → 1 Feb 2027',
      'Status: Not started → Done',
      'Priority: set by the planner → High',
      'Category: Venue → Flowers and decor',
      'Vendor: none → Riverside Barn',
      'Amount: 12,000.00 zł → 2,500.50 zł',
      'Kind of payment: Deposit → Final payment',
      'Paid on: none → 10 Nov 2026',
      'Priority: Low → set by the planner',
      'Notes: By transfer → none',
      'Due: 1 Mar 2027 → none',
      'Category: Rings → none',
      'Vendor: Jeweller → none',
      'Amount: 50.00 zł → none',
      'Title: Rings → Order the rings',
    ]) {
      expect(screen.getByText(line.replaceAll('\u00a0', ' '))).toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: 'Change 2 tasks' })).toBeInTheDocument()
    // Only a number for what stays as it is.
    expect(screen.queryByText('Send thanks')).not.toBeInTheDocument()
  })

  it('cuts long notes short', async () => {
    const server = planningServer([])
    open()
    const long = 'x'.repeat(500)

    await choose(server, Response.json(report({
      rows: [row({ row: 1, outcome: 'CHANGED', changes: [{ field: 'notes', from: '', to: long }] })],
    })))

    expect(await screen.findByText(`Notes: none → ${'x'.repeat(120)}…`)).toBeInTheDocument()
  })

  it('words the button for new and changed tasks, and for one of each', async () => {
    const server = planningServer([])
    open()

    await choose(server, Response.json(report({
      rows: [row({ row: 1 }), row({ row: 2 }), row({ row: 3 }), row({ row: 4, outcome: 'CHANGED', changes: [{ field: 'title', from: 'a', to: 'b' }] }),
        row({ row: 5, outcome: 'CHANGED', changes: [{ field: 'title', from: 'a', to: 'b' }] })],
    })))
    expect(await screen.findByRole('button', { name: 'Add 3 tasks and change 2' })).toBeInTheDocument()

    await choose(server, Response.json(report({
      rows: [row({ row: 1 }), row({ row: 4, outcome: 'CHANGED', changes: [{ field: 'title', from: 'a', to: 'b' }] })],
    })))
    expect(await screen.findByRole('button', { name: 'Add 1 task and change 1' })).toBeInTheDocument()
    expect(screen.getByText('The file has 1 new task and 1 task that would change.')).toBeInTheDocument()
  })

  it('says a file that changes nothing, with nothing to press', async () => {
    const server = planningServer([])
    open()

    await choose(server, Response.json(report({ rows: [row({ row: 1, outcome: 'UNCHANGED' }), row({ row: 2, outcome: 'UNCHANGED' })] })))

    expect(await screen.findByText('The file changes nothing. There is nothing to apply.')).toBeInTheDocument()
    expect(within(part()).queryByRole('button')).not.toBeInTheDocument()
  })

  it('lists at most a hundred new and a hundred changed tasks, and says how many more', async () => {
    const server = planningServer([])
    open()
    const rows = [
      ...Array.from({ length: 137 }, (_, index) => row({ row: index + 1, title: `New ${index + 1}` })),
      ...Array.from({ length: 103 }, (_, index) =>
        row({ row: 200 + index, title: `Changed ${index + 1}`, outcome: 'CHANGED', changes: [{ field: 'title', from: 'a', to: 'b' }] })),
    ]

    await choose(server, Response.json(report({ rows })))

    expect(await screen.findByText('The file has 137 new tasks and 103 tasks that would change.')).toBeInTheDocument()
    expect(screen.getByText('New 100')).toBeInTheDocument()
    expect(screen.queryByText('New 101')).not.toBeInTheDocument()
    expect(screen.getByText('and 37 more')).toBeInTheDocument()
    expect(screen.getByText('Changed 100')).toBeInTheDocument()
    expect(screen.queryByText('Changed 101')).not.toBeInTheDocument()
    expect(screen.getByText('and 3 more')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add 137 tasks and change 103' })).toBeInTheDocument()
  })

  describe('when something cannot be taken', () => {
    it('lists the problems of the file as a whole and of its rows, and offers no button', async () => {
      const server = planningServer([])
      open()

      await choose(server, Response.json(report({
        canBeApplied: false,
        problems: [{ message: 'This is not a file of tasks in this format.' }],
        rows: [
          row({ row: 1 }),
          row({ row: 2, key: 'pay-venue', outcome: 'REFUSED', problems: [{ field: 'dueDate', message: 'Enter a real day.' }, { field: null, message: 'Something about the row.' }] }),
          row({ row: 3, key: null, title: null, outcome: 'REFUSED', problems: [{ field: 'duedate', message: 'The format has no field “duedate”.' }] }),
        ],
      })))

      expect(await screen.findByText('Nothing can be applied until the file is corrected.')).toBeInTheDocument()
      const wrong = within(part()).getByRole('heading', { name: 'What is wrong' }).parentElement as HTMLElement
      expect(within(wrong).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
        'This is not a file of tasks in this format.',
        'Row 2, “pay-venue” — Due: Enter a real day.',
        'Row 2, “pay-venue” — Something about the row.',
        'Row 3 — duedate: The format has no field “duedate”.',
      ])
      expect(within(part()).queryByRole('button')).not.toBeInTheDocument()
      expect(screen.queryByText('Task 1')).not.toBeInTheDocument()
    })

    it('lists at most a hundred problems and says how many more', async () => {
      const server = planningServer([])
      open()
      const rows = Array.from({ length: 130 }, (_, index) =>
        row({ row: index + 1, outcome: 'REFUSED', problems: [{ field: 'title', message: `Wrong ${index + 1}.` }] }))

      await choose(server, Response.json(report({ canBeApplied: false, rows })))

      expect(await screen.findByText(/Wrong 100\./)).toBeInTheDocument()
      expect(screen.queryByText(/Wrong 101\./)).not.toBeInTheDocument()
      expect(screen.getByText('and 30 more problems')).toBeInTheDocument()
    })
  })

  it('shows what comes out of a file as the text it is', async () => {
    const server = planningServer([])
    open()
    const markup = '<img src=x onerror=alert(1)><b>bold</b> <a href="https://evil.example">link</a>'

    await choose(server, Response.json(report({
      newCategories: ['<i>Cat</i>'],
      rows: [
        row({ row: 1, title: markup }),
        row({ row: 2, title: 'Changed', outcome: 'CHANGED', changes: [{ field: 'vendor', from: '<u>old</u>', to: '<s>new</s>' }] }),
      ],
    })))

    expect(await screen.findByText(markup)).toBeInTheDocument()
    expect(screen.getByText('Vendor: <u>old</u> → <s>new</s>')).toBeInTheDocument()
    expect(screen.getByText('This category would be added: <i>Cat</i>.')).toBeInTheDocument()
    expect(part().querySelector('img, b, i, u, s')).toBeNull()
    expect(within(part()).queryByRole('link', { name: 'link' })).not.toBeInTheDocument()
  })

  it('shows the key, the field and the message of a problem as plain text', async () => {
    const server = planningServer([])
    open()

    await choose(server, Response.json(report({
      canBeApplied: false,
      problems: [{ message: '<b>Not</b> a file' }],
      rows: [row({ row: 1, key: '<a href="https://evil.example">k</a>', outcome: 'REFUSED', problems: [{ field: '<i>f</i>', message: '<em>bad</em>' }] })],
    })))

    expect(await screen.findByText('<b>Not</b> a file')).toBeInTheDocument()
    expect(screen.getByText('Row 1, “<a href="https://evil.example">k</a>” — <i>f</i>: <em>bad</em>')).toBeInTheDocument()
    expect(part().querySelector('a[href^="https://evil"], em, i, b')).toBeNull()
  })

  describe('applying', () => {
    const good = report({
      rows: [row({ row: 1, title: 'Book the flights' }), row({ row: 2, title: 'Pay', outcome: 'CHANGED', changes: [{ field: 'title', from: 'a', to: 'b' }] })],
    })

    it('sends the file once for one press, then says what was done, reads the list and the categories again, and is ready for the next', async () => {
      let release: () => void = () => undefined
      const server = planningServer([], {
        [CHECK]: Response.json(good),
        [APPLY]: () => new Promise<Response>((resolve) => {
          release = () => resolve(Response.json(report({ applied: true, added: 1, changed: 1, rows: good.rows })))
        }),
      })
      open()
      await choose(null, null)
      const press = await screen.findByRole('button', { name: 'Add 1 task and change 1' })
      const listBefore = server.calls.filter((call) => call === 'GET /api/entries').length
      const categoriesBefore = server.calls.filter((call) => call === 'GET /api/categories').length

      await userEvent.dblClick(press)

      expect(await screen.findByRole('button', { name: 'Applying…' })).toBeDisabled()
      expect(screen.getByText('Applying the file…')).toBeInTheDocument()
      release()
      expect(await screen.findByText('Added 1 task and changed 1.')).toBeInTheDocument()

      expect(server.requests(APPLY)).toHaveLength(1)
      expect(await server.requests(APPLY)[0]?.text()).toBe(FILE_TEXT)
      expect(server.requests(CHECK)).toHaveLength(1)
      await waitFor(() => {
        expect(server.calls.filter((call) => call === 'GET /api/entries').length).toBeGreaterThan(listBefore)
        expect(server.calls.filter((call) => call === 'GET /api/categories').length).toBeGreaterThan(categoriesBefore)
      })
      // The file is let go and the keyboard is where the sentence is.
      expect(within(part()).queryByRole('button', { name: /Add|Change|Applying/ })).not.toBeInTheDocument()
      expect(screen.queryByText('File: tasks.json')).not.toBeInTheDocument()
      expect(screen.queryByText('Book the flights')).not.toBeInTheDocument()
      await waitFor(() => expect(screen.getByText('Added 1 task and changed 1.')).toHaveFocus())
      expect(screen.getByLabelText('Choose a file')).toBeEnabled()

      // A second file starts from the beginning and takes the sentence away.
      server.answer(CHECK, Response.json(report({ rows: [row({ row: 1, title: 'Another' })] })))
      await userEvent.upload(screen.getByLabelText('Choose a file'), file('{}', 'next.json'))
      expect(await screen.findByText('The file has 1 new task.')).toBeInTheDocument()
      expect(screen.queryByText('Added 1 task and changed 1.')).not.toBeInTheDocument()
      expect(server.requests(CHECK)).toHaveLength(2)
    })

    it('says when a file turned out to change nothing', async () => {
      planningServer([], {
        [CHECK]: Response.json(good),
        [APPLY]: Response.json(report({ applied: true, rows: [row({ row: 1, outcome: 'UNCHANGED' })] })),
      })
      open()
      await choose(null, null)

      await userEvent.click(await screen.findByRole('button', { name: 'Add 1 task and change 1' }))

      expect(await screen.findByText('The file changed nothing: every task in it was already as the file says.')).toBeInTheDocument()
    })

    it('says "Added 3 tasks." and "Changed 2 tasks."', async () => {
      const server = planningServer([], {
        [CHECK]: Response.json(report({ rows: [row({ row: 1 }), row({ row: 2 }), row({ row: 3 })] })),
        [APPLY]: Response.json(report({ applied: true, added: 3 })),
      })
      open()
      await choose(null, null)
      await userEvent.click(await screen.findByRole('button', { name: 'Add 3 tasks' }))
      expect(await screen.findByText('Added 3 tasks.')).toBeInTheDocument()

      server.answer(CHECK, Response.json(report({ rows: [row({ row: 1, outcome: 'CHANGED' }), row({ row: 2, outcome: 'CHANGED' })] })))
      server.answer(APPLY, Response.json(report({ applied: true, changed: 2 })))
      await userEvent.upload(screen.getByLabelText('Choose a file'), file())
      await userEvent.click(await screen.findByRole('button', { name: 'Change 2 tasks' }))
      expect(await screen.findByText('Changed 2 tasks.')).toBeInTheDocument()
    })

    it('does not apply by choosing', async () => {
      const server = planningServer([], { [CHECK]: Response.json(good) })
      open()
      await choose(null, null)
      await screen.findByRole('button', { name: 'Add 1 task and change 1' })

      expect(server.calls).not.toContain(APPLY)
    })

    it('is where a person can try again when the planner cannot be reached, with the file still chosen', async () => {
      const server = planningServer([], { [CHECK]: Response.json(good), [APPLY]: new TypeError('Failed to fetch') })
      open()
      await choose(null, null)
      await userEvent.click(await screen.findByRole('button', { name: 'Add 1 task and change 1' }))

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('The planner could not be reached, so the file may not have been applied.')
      expect(server.requests(APPLY)).toHaveLength(1)
      expect(screen.getByText('File: tasks.json')).toBeInTheDocument()
      await waitFor(() => expect(alert).toHaveFocus())

      server.answer(APPLY, Response.json(report({ applied: true, added: 1, changed: 1 })))
      await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

      expect(await screen.findByText('Added 1 task and changed 1.')).toBeInTheDocument()
      expect(server.requests(APPLY)).toHaveLength(2)
      expect(await server.requests(APPLY)[1]?.text()).toBe(FILE_TEXT)
      // No new choice was needed: the file was checked only once.
      expect(server.requests(CHECK)).toHaveLength(1)
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('checks the file again when the planner changed since the check, and says what is wrong now', async () => {
      const server = planningServer([], { [CHECK]: Response.json(good), [APPLY]: problem(422, 'task-file-refused', 'Refused') })
      open()
      await choose(null, null)
      const press = await screen.findByRole('button', { name: 'Add 1 task and change 1' })
      server.answer(CHECK, Response.json(report({
        canBeApplied: false,
        rows: [row({ row: 2, key: 'pay', outcome: 'REFUSED', problems: [{ field: 'vendor', message: 'The vendor “Barn” is not in the planner.' }] })],
      })))
      await userEvent.click(press)

      expect(await screen.findByText('Row 2, “pay” — Vendor: The vendor “Barn” is not in the planner.')).toBeInTheDocument()
      expect(screen.getByRole('alert')).toHaveTextContent('The planner has changed since this file was checked, so nothing was applied.')
      expect(server.requests(CHECK)).toHaveLength(2)
      expect(server.requests(APPLY)).toHaveLength(1)
      expect(within(part()).queryByRole('button', { name: /Add|Change/ })).not.toBeInTheDocument()
      await waitFor(() => expect(screen.getByRole('alert')).toHaveFocus())
    })

    it('shows the new button when the file checked again can still be applied', async () => {
      const server = planningServer([], { [CHECK]: Response.json(good), [APPLY]: problem(422, 'task-file-refused', 'Refused') })
      open()
      await choose(null, null)
      const press = await screen.findByRole('button', { name: 'Add 1 task and change 1' })
      server.answer(CHECK, Response.json(report({ rows: [row({ row: 1 })] })))
      await userEvent.click(press)

      expect(await screen.findByRole('button', { name: 'Add 1 task' })).toBeInTheDocument()
    })

    it('says that the server finds the file too large', async () => {
      const server = planningServer([], { [CHECK]: Response.json(good), [APPLY]: problem(413, 'upload-too-large', 'Too large') })
      open()
      await choose(null, null)
      await userEvent.click(await screen.findByRole('button', { name: 'Add 1 task and change 1' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('The planner does not take a file this large: 1 MB is the most.')
      expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
      expect(server.requests(APPLY)).toHaveLength(1)
    })

    it('says "some error" for any other problem and keeps the file for another try', async () => {
      const server = planningServer([], { [CHECK]: Response.json(good), [APPLY]: problem(500, 'internal-error', 'Broken') })
      open()
      await choose(null, null)
      await userEvent.click(await screen.findByRole('button', { name: 'Add 1 task and change 1' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('Some error came back from the planner, so the file was not applied. Nothing was changed.')
      expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
      expect(server.requests(APPLY)).toHaveLength(1)
    })
  })

  describe('what can go wrong with the file', () => {
    it('says a file above the limit before anything is sent', async () => {
      const server = planningServer([])
      open()
      const big = file('{}', 'big.json')
      Object.defineProperty(big, 'size', { value: 1_048_577 })

      await userEvent.upload(await screen.findByLabelText('Choose a file'), big)

      expect(await screen.findByRole('alert')).toHaveTextContent('“big.json” is larger than 1 MB, the most the planner takes. Nothing was sent.')
      expect(server.calls).not.toContain(CHECK)
      expect(server.calls).not.toContain(APPLY)
      expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
    })

    it('takes a file of exactly the limit', async () => {
      const server = planningServer([], { [CHECK]: Response.json(report({ rows: [row({ row: 1 })] })) })
      open()
      const exact = file('{}')
      Object.defineProperty(exact, 'size', { value: 1_048_576 })

      await userEvent.upload(await screen.findByLabelText('Choose a file'), exact)

      expect(await screen.findByText('The file has 1 new task.')).toBeInTheDocument()
      expect(server.requests(CHECK)).toHaveLength(1)
    })

    it('says a file that the browser cannot read, and sends nothing', async () => {
      const server = planningServer([])
      open()
      const unreadable = file('{}', 'locked.json')
      Object.defineProperty(unreadable, 'text', { value: () => Promise.reject(new Error('NotReadableError')) })

      await userEvent.upload(await screen.findByLabelText('Choose a file'), unreadable)

      expect(await screen.findByRole('alert')).toHaveTextContent('Your browser could not read “locked.json”.')
      expect(server.calls).not.toContain(CHECK)
    })

    it('starts over when another file is chosen, and forgets what was said of the first', async () => {
      const server = planningServer([], { [CHECK]: Response.json(report({ canBeApplied: false, problems: [{ message: 'Not a file of tasks.' }] })) })
      open()
      await choose(null, null, file('nonsense', 'one.json'))
      expect(await screen.findByText('Not a file of tasks.')).toBeInTheDocument()

      server.answer(CHECK, Response.json(report({ rows: [row({ row: 1, title: 'Fresh' })] })))
      await userEvent.upload(screen.getByLabelText('Choose a file'), file('{"a":1}', 'two.json'))

      expect(await screen.findByText('Fresh')).toBeInTheDocument()
      expect(screen.queryByText('Not a file of tasks.')).not.toBeInTheDocument()
      expect(screen.getByText('File: two.json')).toBeInTheDocument()
      expect(await server.requests(CHECK)[1]?.text()).toBe('{"a":1}')
    })

    it('can choose the same file again once it was corrected', async () => {
      const server = planningServer([], { [CHECK]: Response.json(report({ canBeApplied: false, problems: [{ message: 'Not a file of tasks.' }] })) })
      open()
      const input = await screen.findByLabelText('Choose a file')
      await userEvent.upload(input, file())
      await screen.findByText('Not a file of tasks.')

      server.answer(CHECK, Response.json(report({ rows: [row({ row: 1 })] })))
      await userEvent.upload(screen.getByLabelText('Choose a file'), file())

      expect(await screen.findByText('The file has 1 new task.')).toBeInTheDocument()
      expect(input).toHaveValue('')
    })

    it('says that the planner cannot be reached at the check, and tries again without a new choice', async () => {
      const server = planningServer([], { [CHECK]: new TypeError('Failed to fetch') })
      open()
      await choose(null, null)

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('The planner could not be reached, so the file was not checked. Nothing was changed.')
      expect(server.requests(CHECK)).toHaveLength(1)
      expect(screen.getByText('File: tasks.json')).toBeInTheDocument()

      server.answer(CHECK, Response.json(report({ rows: [row({ row: 1 })] })))
      await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

      expect(await screen.findByText('The file has 1 new task.')).toBeInTheDocument()
      expect(server.requests(CHECK)).toHaveLength(2)
      expect(await server.requests(CHECK)[1]?.text()).toBe(FILE_TEXT)
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('says that the server finds the file too large at the check', async () => {
      planningServer([], { [CHECK]: problem(413, 'upload-too-large', 'Too large') })
      open()
      await choose(null, null)

      expect(await screen.findByRole('alert')).toHaveTextContent('The planner does not take a file this large: 1 MB is the most.')
      expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
    })

    it('says "some error" for any other problem at the check', async () => {
      planningServer([], { [CHECK]: problem(500, 'internal-error', 'Broken') })
      open()
      await choose(null, null)

      expect(await screen.findByRole('alert')).toHaveTextContent('Some error came back from the planner, so the file was not checked.')
      expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    })
  })
})
