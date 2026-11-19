# Deployment: the planner as a container

The planner ships as **one image**: the backend, with the built web app inside it. It needs a PostgreSQL 18
database next to it and a proxy in front of it that ends TLS. Which server it runs on, under which address and
with which values is the owner's business and is not in this repository.

## Building the image

From the repository root:

```bash
docker build -t wedding-planner .
```

The `Dockerfile` has three stages: the web app (`npm ci`, `npm run build`), the backend (`./gradlew bootJar`) and
the image that runs. The first two run on the machine that builds, whatever machine the image is for, so building
for the server's processor (`--platform linux/arm64`) stays fast. The publishing step (WP-TS-28) passes the commit
as `--build-arg REVISION=<sha>`; it ends up in the label `org.opencontainers.image.revision`.

## Publishing the image

The owner publishes, from the Mac, by running one script (decision D-002). Nothing else does.

```bash
scripts/publish-image.sh
```

It refuses unless the checkout is on `main`, has no changes of its own and is exactly what the repository's `main`
is. Then it runs the checks for the backend and the web app (`--full-gate` adds the Android part), builds the image
for `linux/arm64` and pushes it twice: tagged with the full commit, and as `latest`. The commit is also in the
image's label `org.opencontainers.image.revision`. `--no-push` does everything but the push.

- **Name.** `ghcr.io/<owner>/<repository>`, worked out from the address of the remote `origin`. Give `IMAGE=…`
  to publish somewhere else.
- **Once, before the first run:** `docker login ghcr.io`, with a token that may write packages (for GitHub's
  registry a classic personal access token with `write:packages`). Docker keeps it in the keychain; the script
  never sees it.
- **The first push creates the package as private.** Either the server logs in too, with a token that may only
  read packages, or the package is made public on GitHub: it contains nothing that is not in this repository.
- **Going back.** Every published commit keeps its own tag. The server runs an older one by naming that commit
  as the tag instead of `latest`.
- The Mac needs Docker with its daemon running, and what the gate needs: JDK 25 and Node 22.

## Trying it on one machine

`deploy/compose.yaml` starts the image next to a PostgreSQL 18 of its own, locked down the way the real server
should run it.

```bash
cp deploy/env.example deploy/.env
```

Fill `deploy/.env` in (it is ignored by git), then:

```bash
docker compose -f deploy/compose.yaml up --build
```

The planner is at `http://localhost:8080` once `docker ps` shows the app as `healthy`. With made-up addresses in
`ALLOWED_EMAILS` and `DEV_LOGIN_ENABLED=true` it can be signed in to without Google — on your own machine only.

## What the container needs

| | |
|---|---|
| Port | `8080`, plain HTTP. TLS ends at the proxy in front. |
| User | uid and gid `10001`, never root. |
| File system | Can be read-only. The process writes to `/tmp` (give it a tmpfs) and to the volume below. |
| Volume | `/data/media` (`APP_MEDIA_DIR`): the pictures of inspirations, in `inspirations/`, one file per picture plus a smaller version of it, each named after an id. `incoming/` holds uploads only while they arrive. Back it up together with the database. |
| Uploads | One picture up to 15 MB, one request up to 100 MB. The proxy in front has to let requests of that size through. |
| Database | PostgreSQL 18. The schema is brought up to date at every start (Flyway), so there is no separate migration step. A change that removes something is shipped in two releases: first stop using it, then drop it. |
| Health | `GET /actuator/health` answers 200 only while the database can be reached, and 503 within five seconds when it cannot. The image carries a health check that asks exactly that. |
| Logs | Standard output. People appear by id only, never by name or address. |
| Memory | The Java heap takes 60 % of the container's memory limit (`JAVA_TOOL_OPTIONS`). 1 GB is plenty for two people. |
| Goes out to | `www.googleapis.com`, for Google's signing keys at sign-in. Any public web address, to fetch the title and preview picture of a link someone saved; never an address inside the server's own network (decision D-003). The Anthropic API and Gmail join with epic 7. |

## Settings

Everything that differs between installations is an environment variable. `deploy/env.example` lists the ones an
installation has to fill in, without values.

| Variable | Needed | What it is |
|---|---|---|
| `DATABASE_URL` | yes | `jdbc:postgresql://<host>:5432/<database>` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | yes | The database account. It owns the schema: migrations run as this user. |
| `APP_JWT_SECRET` | yes | Signs the access tokens. At least 32 characters, random. Changing it signs everyone out. |
| `ALLOWED_EMAILS` | yes | The addresses that may sign in, separated by commas: the two owners. Empty means nobody. |
| `GOOGLE_OAUTH_WEB_CLIENT_ID` | for Google sign-in | The client id of the Google OAuth web client (WP-TS-14). Empty means nobody can sign in with Google. |
| `GOOGLE_OAUTH_CLIENT_SECRET` | for the e-mail import | The secret of the same Google client. Never in the repository, never in a log. |
| `APP_PUBLIC_URL` | for the e-mail import | The planner's own address as people reach it, `https://…` without a path. Google sends the browser back to `<this>/api/mail/gmail/return`. |
| `APP_MAIL_TOKEN_KEY` | for the e-mail import | 32 random bytes, base64 (`openssl rand -base64 32`): the key the mailboxes' tokens are encrypted with. It is deliberately not in the database: a copy of the database alone opens nobody's mail. **Keep it with the backups' secrets, not with the backups.** Losing or changing it means both owners connect their mail again. |
| `MAIL_SYNC_INTERVAL` | no, default `5m` | How often the server looks at the connected mailboxes. |
| `MAIL_SYNC_ENABLED` | no, default `true` | `false`: connected mailboxes are not looked at by themselves. Connecting and disconnecting still work. |
| `APP_AI_API_KEY` | for reading the mail | The key of the account at the AI service (Anthropic). Empty: no message is read and nothing is sent to the service. Never in the repository, never in a log. |
| `APP_AI_TRIAGE_MODEL`, `APP_AI_EXTRACTION_MODEL` | no, defaults `claude-haiku-5-5` and `claude-opus-5-5` | The two models (D-007), written exactly so. |
| `APP_AI_TRIAGE_PRICE_IN`, `APP_AI_TRIAGE_PRICE_OUT`, `APP_AI_EXTRACTION_PRICE_IN`, `APP_AI_EXTRACTION_PRICE_OUT` | no, defaults `0.10`, `0.50`, `4`, `20` | US dollars per million tokens, by step: what the estimate of the cost is worked out from. Set them anew when the service's prices change. |
| `MAIL_READING_MONTHLY_LIMIT_USD` | no, default `20` | When the month's estimate reaches it, the reading pauses until the next month. |
| `MAIL_READING_DAILY_MESSAGES` | no, default `2000` | How many messages are read in a day at most. |
| `MAIL_READING_DAILY_SUGGESTIONS` | no, default `50` | How many new suggestions one mailbox may bring in a day; then that mailbox waits until the next day. |
| `MAIL_READING_TRIAGE_SURE` | no, default `60` | A "not about the wedding" that is less sure than this (0 to 100) is read in full all the same. |
| `MAIL_READING_INTERVAL` | no, default `1m` | How often the server reads what waits, twenty messages at a time. |
| `MAIL_READING_ENABLED` | no, default `true` | `false`: nothing is read by itself. |
| `DEV_LOGIN_ENABLED` | no, default `false` | Signing in as an allowed address without Google. **Never `true` on a server others can reach.** |
| `PREVIEWS_ENABLED` | no, default `true` | `false`: the server never goes out to fetch a preview for a saved link. Links are kept without one. |
| `API_DOCS_ENABLED` | no, default `false` | Serves the API description at `/v3/api-docs`. For building clients, not for production. |
| `APP_WEB_DIR` | set in the image | Where the web app's files are (`/app/web`). |
| `APP_MEDIA_DIR` | set in the image | Where kept files go (`/data/media`). The server does not start without it, nor when it cannot write there. |
| `JAVA_TOOL_OPTIONS` | set in the image | Java's own options; replace it to change the heap share. |

No time zone setting is needed: every date is read in the wedding's own time zone, and logs are in UTC.

## The e-mail import

The import (S03) is **off unless the server has all four**: `GOOGLE_OAUTH_WEB_CLIENT_ID`,
`GOOGLE_OAUTH_CLIENT_SECRET`, `APP_PUBLIC_URL` and `APP_MAIL_TOKEN_KEY`. With one missing or not valid the server
starts as before, says once in its log which one (the name, never a value), and the web's e-mail page says the
import is not set up. Nothing is asked of Google.

What the owner does once, in the Google project that already holds the sign-in client (D-006 says why):

1. **Switch the Gmail API on** for the project (APIs and services → Library → Gmail API).
2. **Add the return address** to the web client's authorised redirect addresses:
   `<APP_PUBLIC_URL>/api/mail/gmail/return`, letter for letter.
3. **Add the scope** `https://www.googleapis.com/auth/gmail.readonly` to the consent screen's scopes. No other
   Gmail scope: the planner only reads.
4. **Set the consent screen's publishing status to "In production"** and leave the app unverified. Left in
   "Testing", every connection ends after seven days. Google will show "Google hasn't verified this app" when a
   mailbox is connected: go on through "Advanced". The planner's page says so beforehand.
5. Copy the client's **secret** into the server's environment file, make the **key** with
   `openssl rand -base64 32`, set the public address, and start the server again.
6. On the web, each owner opens **E-mail import** and connects their mailbox. Check again after more than a
   week that both are still connected. If Google refuses, or the connection keeps ending, the route does not
   hold for these accounts: say so — the fallback is recorded in D-006.

To withdraw the planner's access without the planner: Google account → Security → Third-party access.
Disconnecting on the planner's page withdraws it as well and removes the token from the server.

At a log level below INFO the web server and the database layer would print request lines, cookies and
statement values. The loggers that do are held at INFO in the server's own settings; leave them there.

## Reading the mail

Reading (S04) is the second half of the import: of every noted message the sender, the subject and the beginning
go to the AI service, which says whether it concerns the wedding; a message that does is sent in full and comes
back as proposals that wait in the suggestions inbox. S04 section 3 lists exactly what leaves the server.

It is **off unless the server has `APP_AI_API_KEY`** and the e-mail import above is set up. Off, the server notes
messages as before and sends nothing anywhere. A setting that is not valid also turns it off; the log names the
setting, never its value.

What the owner does once:

1. In the Anthropic console, make **an API key** for the planner alone (a workspace of its own keeps its use
   apart from anything else on the account).
2. Set **a monthly spending limit** on that account or workspace. The server has its own limit
   (`MAIL_READING_MONTHLY_LIMIT_USD`), but that one is worked out from an estimate; the account's limit is the
   one that cannot be passed.
3. Put the key into the server's environment file as `APP_AI_API_KEY` and start the server again.
4. Open **E-mail import** on the web: it says that the mail is being read, how far it is, and what it has cost.

What to expect, and what to do:

- **The first look** over three months of two mailboxes is spread over days: at most 2,000 messages a day, and
  at most 50 new suggestions from one mailbox in a day. D-007 estimates about 3 US dollars for a thousand
  messages; the page shows what it really comes to.
- **The service is busy**: the reading waits, for longer each time, and goes on by itself.
- **The key is not accepted** (a wrong key, no credit, a model the account does not know): the reading stops and
  the page says so. Correct the setting and **start the server again** — it does not try again by itself.
- **A limit is reached**: the page says which, and the reading goes on the next day or the next month.
- **The log** says that a message was read, by which step, what it came to, the model and the tokens — never
  which message, who sent it, or a word of it. The AI service's SDK and its HTTP client are held at INFO in the
  server's own settings for the same reason; leave them there.

Nothing of this has run against the real service yet: the first days are worth watching on the page.

## Behind the proxy

- The proxy must pass `X-Forwarded-Proto` and `X-Forwarded-Host`. The backend then knows it is reached over
  HTTPS, and marks the sign-in cookie `Secure`. Without the header the cookie is not marked, which is right for
  `http://localhost` and wrong anywhere else.
- The public address has to be listed under "Authorised JavaScript origins" of the Google OAuth client.
- The backend does no rate limiting and blocks no addresses; that is the proxy's part. Who may sign in is
  decided by `ALLOWED_EMAILS` alone.
- Do not publish port 8080 on the server; let the proxy reach it over a private network.
- Outgoing requests for link previews must not be routed through an HTTP proxy: the planner checks the address it
  connects to itself, and a proxy would take that out of its hands (decision D-003). If they must be, set
  `PREVIEWS_ENABLED=false`.

## Runbook

For whoever looks after the running planner. The commands are run on the server, in the directory that holds the
planner's `compose.yaml` and its `.env`. They use the names of `deploy/compose.yaml`: the services `postgres` and
`app`, the database `weddingplanner`. If the server's file names them differently, use its names.

Several commands need the database account's name. Take it from `.env` once per terminal:

```bash
set -a; . ./.env; set +a
```

### What runs

| | |
|---|---|
| `app` | The planner's image: the backend and the web app. Keeps nothing but the files under `/data/media`. |
| `postgres` | PostgreSQL 18 with everything else: both accounts, the wedding, the plan, the tasks. |
| In front | The proxy that ends TLS and passes requests to `app` on port 8080. Not part of this project. |
| `.env` | The settings and the secrets, filled in by hand. It exists on the server only. |

```bash
docker compose ps
```

shows both services; `app` must say `healthy`. Which commit is running:

```bash
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$(docker compose ps -q app)"
```

Both services restart by themselves after the server restarts (`restart: unless-stopped`).

### An update

1. On the Mac, from a checkout of `main`: `scripts/publish-image.sh` (see "Publishing the image").
2. On the server:

```bash
docker compose pull app
```

```bash
docker compose up -d app
```

3. `docker compose ps` until `app` is `healthy`, usually within half a minute. Then the commit shown by the
   command above is the one just published.

The database is brought up to date by the new image as it starts. If `app` does not become healthy, read its
logs, and roll back.

### Rolling back

Every published commit keeps a tag of its own: the full commit id. Rolling back means running an earlier one.

1. Find the commit to go back to. On the Mac, `git log --oneline main` lists them, newest first; the commit
   that ran before the update is the one to take. `git rev-parse <short id>` prints its full id.
2. On the server, in `.env`, set the tag to that full id:

```bash
IMAGE_TAG=<the full commit id>
```

3. Then, as for an update:

```bash
docker compose pull app
```

```bash
docker compose up -d app
```

4. `docker compose ps` until `app` is `healthy`, and check the running commit with the command under "What runs".

To follow `main` again later, set `IMAGE_TAG=latest` and repeat step 3.

**When the update changed the database.** An older image starts in front of a database that a newer one has
already changed; it says so in a warning and carries on. Whether it then works depends on the change: something
added does no harm, something removed or given a new meaning does. To see whether an update touched the database:

```bash
git diff --stat <older commit> <newer commit> -- backend/src/main/resources/db/migration
```

No output means the two fit the same database and the rollback is safe. Otherwise prefer a fix published as a
new commit; if it has to be the old image, restore the backup taken before the update as well.

### Logs

```bash
docker compose logs --since 1h app
```

Add `--follow` to watch. People appear by id only. A start that failed ends with the reason, usually a setting
that is missing or a database that cannot be reached. What the health check last saw:

```bash
docker inspect --format '{{json .State.Health}}' "$(docker compose ps -q app)"
```

### Backup

Two things hold data: the database, and the pictures under `/data/media`. Both, every night, taken one right
after the other and kept somewhere that is not this server. The settings in `.env` are not in any backup: keep a
copy of that file in a password manager.

The database, as one file:

```bash
docker compose exec -T postgres pg_dump --username "$DATABASE_USER" --format=custom weddingplanner > planner-$(date +%F).dump
```

The pictures, as one file:

```bash
docker compose exec -T app tar -C /data/media -cf - inspirations > media-$(date +%F).tar
```

### Proving that a backup can be restored

Into a scratch database next to the real one, which is not touched:

```bash
docker compose exec -T postgres createdb --username "$DATABASE_USER" scratch
```

```bash
docker compose exec -T postgres pg_restore --username "$DATABASE_USER" --dbname scratch --no-owner < planner-<date>.dump
```

```bash
docker compose exec -T postgres psql --username "$DATABASE_USER" --dbname scratch --command "select count(*) from entry"
```

The count is the number of plan items and tasks at the time of the backup. Then remove the scratch database:

```bash
docker compose exec -T postgres dropdb --username "$DATABASE_USER" scratch
```

### Restoring for real

This replaces everything in the database with the backup. Whatever was entered after the backup is gone.

```bash
docker compose stop app
```

```bash
docker compose exec -T postgres dropdb --username "$DATABASE_USER" weddingplanner
```

```bash
docker compose exec -T postgres createdb --username "$DATABASE_USER" weddingplanner
```

```bash
docker compose exec -T postgres pg_restore --username "$DATABASE_USER" --dbname weddingplanner --no-owner < planner-<date>.dump
```

```bash
docker compose start app
```

The pictures go back while the app runs. They are unpacked by the app's own user, so they stay its files:

```bash
docker compose exec -T app tar -C /data/media -xf - < media-<date>.tar
```

Restore the pictures from the same night as the database. The database says which pictures exist and the files
are the pictures: a picture that is recorded but not on the disk shows as missing, and a file nobody records
only takes space.

### Small things

- **Who may sign in** is `ALLOWED_EMAILS` in `.env`. After a change: `docker compose up -d app`.
- **Signing everyone out**, for instance after a phone was lost: give `APP_JWT_SECRET` a new random value, then
  `docker compose up -d app`. Both owners sign in again.
- **The development login** must stay off on the server: `DEV_LOGIN_ENABLED=false`. If the log of a start says
  that it is ON, turn it off at once.

## How this was checked

On 2026-11-17, in the session that wrote it:

- The image was built from the `Dockerfile` and run with `deploy/compose.yaml` and PostgreSQL 18: healthy about
  ten seconds after start; running as 10001 on a read-only file system with all capabilities dropped; unhealthy
  after the database was stopped and healthy again after it came back; data still there after everything was
  taken down and started again; the `Secure` cookie present with `X-Forwarded-Proto: https` and absent without.
- `scripts/publish-image.sh` was run from a clean clone against a registry started for the purpose. It published
  the commit under both tags, and the image pulled back from that registry ran healthy: once built for x86-64,
  and once for `linux/arm64`, the server's kind, run under emulation. Each of its refusals was tried: another
  branch, uncommitted changes, a commit the repository does not have, a name with a tag, Docker not running.
- The runbook's commands were run in order against two images published to that registry: start from `latest`,
  an update, a rollback by commit id, the logs, a backup, a restore into a scratch database with the counts
  compared, a deliberate loss put right by a full restore, an older image started in front of a database marked
  as changed by a newer one, and a restart of both containers.

On 2026-11-18 the image with the pictures of inspirations was run the same way: two pictures uploaded through
the API ended up on the volume, owned by 10001, with the root file system read-only; they came back byte for byte
and were refused without sign-in. The pictures were then packed with the backup command above, deleted, unpacked
again with the restore command, served again, and a new one could still be added.

Not done yet: a run of the publishing script on the owner's Mac that pushes to the real registry; the server
pulling from it; the nightly backup on the server and a restore from one of its files; and the owner rolling
back from this text alone.
