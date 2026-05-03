# Deploy a Wun app with Docker

## When to use this

The user wants to ship a Wun app to a real environment -- a docker
host, fly.io, render, k8s, etc. They've either:

- generated the app with `wun new app foo --docker [...]` (in which
  case the Dockerfile + `docker-compose.yml` + `build.clj` are
  already there), OR
- generated the app without `--docker` and now wants to add
  deployment support after the fact.

Pick this skill when the user mentions "deploy", "docker",
"production", "fly.io", "uberjar", or wants the app to run somewhere
other than `wun dev`.

## Inputs you need

- App name / dir (so you can `cd` into it).
- Whether they're using a database (`--db sqlite|postgres|datomic`)
  -- the docker-compose override differs per backend.
- Where they want to deploy (docker host? fly.io? something else?).

## Steps

### 1. If the app was scaffolded WITHOUT `--docker`

Re-run the generator with `--docker` into a scratch dir, then copy
the new files into the existing app:

```bash
cd ..
wun new app _docker_overlay --docker [--db <same-db-they-have>]
cp _docker_overlay/Dockerfile .dockerignore docker-compose.yml \
   build.clj .env.example fly.toml <real-app>/
cp -R _docker_overlay/.github <real-app>/
rm -rf _docker_overlay
```

Then patch the existing `deps.edn` to add the `:build` alias (see the
`build.clj` ns docstring for the alias shape).

### 2. Build locally first

```bash
cd <app>
docker build -t <app>:latest .
docker run --rm -p 8080:8080 <app>:latest
curl -sf http://localhost:8080/healthz   # expect {"status":"ok"}
```

If the build fails on the `clojure -T:build uber` step, the most
common cause is that the `:build` alias in `deps.edn` references a
stale tools.build sha. Pin it to the latest release.

### 3. Bring up the full stack with compose

```bash
cp .env.example .env
# edit .env: set SESSION_SECRET to something real
docker compose up --build
```

For Postgres apps, the override file brings up a `postgres:16-alpine`
service alongside the app and waits for its healthcheck before the
app starts. Verify:

```bash
docker compose exec postgres pg_isready -U <app>
```

For SQLite / Datomic apps, the override mounts a named volume at
`/app/data` so the embedded DB survives container restarts.

### 4. Deploy to fly.io

```bash
fly launch --no-deploy        # accept the generated fly.toml
fly secrets set SESSION_SECRET=$(openssl rand -hex 32)
# Postgres only:
fly pg create
fly pg attach <pg-app-name>   # sets DATABASE_URL automatically
# SQLite / Datomic only:
fly volumes create <app>_data --size 1
# then uncomment the [mounts] block in fly.toml
fly deploy
fly logs
```

The healthcheck path is `/healthz`; fly will mark the deploy as
healthy when that returns 200.

## Verify

- `curl -sf https://<app>.fly.dev/healthz` returns `{"status":"ok"}`.
- The app's home screen renders in a browser.
- For DB-backed apps: the `/notes` page shows a row after you POST one.

## Common gotchas

- **`PORT` mismatch**: fly's proxy sends traffic to the `internal_port`
  in `fly.toml`. The Dockerfile sets `PORT=8080` and `wun.server.http`
  reads `PORT` at startup. Don't override `PORT` in `fly secrets` --
  let the env in fly.toml win.
- **SQLite write contention in compose**: HikariCP is capped at 1 in
  the SQLite fragment for a reason. If you raise it, expect
  `SQLITE_BUSY` under any concurrent write load.
- **Missing volume for embedded DBs**: without the `[mounts]` block,
  the SQLite/Datomic file lives inside the container's writable layer
  and vanishes on every redeploy.
