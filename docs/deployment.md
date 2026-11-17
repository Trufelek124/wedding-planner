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
| Volume | `/data/media` (`APP_MEDIA_DIR`): files that must outlive the container. Nothing is stored there yet; pictures of inspirations arrive with epic 5. Back it up together with the database from then on. |
| Database | PostgreSQL 18. The schema is brought up to date at every start (Flyway), so there is no separate migration step. A change that removes something is shipped in two releases: first stop using it, then drop it. |
| Health | `GET /actuator/health` answers 200 only while the database can be reached, and 503 within five seconds when it cannot. The image carries a health check that asks exactly that. |
| Logs | Standard output. People appear by id only, never by name or address. |
| Memory | The Java heap takes 60 % of the container's memory limit (`JAVA_TOOL_OPTIONS`). 1 GB is plenty for two people. |
| Goes out to | `www.googleapis.com`, for Google's signing keys at sign-in. The Anthropic API and Gmail join with epic 7. |

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
| `DEV_LOGIN_ENABLED` | no, default `false` | Signing in as an allowed address without Google. **Never `true` on a server others can reach.** |
| `API_DOCS_ENABLED` | no, default `false` | Serves the API description at `/v3/api-docs`. For building clients, not for production. |
| `APP_WEB_DIR` | set in the image | Where the web app's files are (`/app/web`). |
| `APP_MEDIA_DIR` | set in the image | Where kept files go (`/data/media`). |
| `JAVA_TOOL_OPTIONS` | set in the image | Java's own options; replace it to change the heap share. |

No time zone setting is needed: every date is read in the wedding's own time zone, and logs are in UTC.

## Behind the proxy

- The proxy must pass `X-Forwarded-Proto` and `X-Forwarded-Host`. The backend then knows it is reached over
  HTTPS, and marks the sign-in cookie `Secure`. Without the header the cookie is not marked, which is right for
  `http://localhost` and wrong anywhere else.
- The public address has to be listed under "Authorised JavaScript origins" of the Google OAuth client.
- The backend does no rate limiting and blocks no addresses; that is the proxy's part. Who may sign in is
  decided by `ALLOWED_EMAILS` alone.
- Do not publish port 8080 on the server; let the proxy reach it over a private network.

## How this was checked

On 2026-11-17 the image was built from the `Dockerfile` and run with `deploy/compose.yaml` and PostgreSQL 18:
healthy about ten seconds after start; running as 10001 on a read-only file system with all capabilities dropped;
unhealthy after the database was stopped and healthy again after it came back; data still there after everything
was taken down and started again; the `Secure` cookie present with `X-Forwarded-Proto: https` and absent without.
The build ran on an x86-64 machine. An image for `linux/arm64` has not been built or run yet (WP-TS-28).
