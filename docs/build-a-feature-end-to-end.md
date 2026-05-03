# Build a feature end to end

This walkthrough adds a "tasks" feature to a freshly-scaffolded Wun
app and gates writes behind the auth flow. By the end you'll have:

- A `tasks` table in your DB (sqlite, postgres, or datomic).
- A `:myapp/tasks` screen at `/tasks` that lists and adds rows.
- A `:myapp/add-task` intent whose morph runs identically on server
  (authoritative -- writes the row, re-reads the table) and client
  (optimistic -- appends to local state instantly).
- An auth gate so only logged-in users can add tasks.
- A nav link from the home screen.

The goal is to make the round-trip visible: how a click on the
client becomes a database row on the server, and how the resulting
state ships back over SSE to update the UI.

## Prerequisites

Scaffold an app with one of the DB backends and `--docker`:

```bash
wun new app foo --db sqlite --docker     # or --db postgres / --db datomic
cd foo
```

You should already have a working `/notes` feature and an auth flow
at `/login` / `/signup`. We're going to mirror that shape.

## The five-file rule

A typical Wun feature touches exactly five places:

| layer                   | file                                | purpose                                          |
|-------------------------|-------------------------------------|--------------------------------------------------|
| persistence             | `resources/migrations/<NNNN>_*.sql` | schema (or `resources/datomic/schema-*.edn`)     |
| server-only data ops    | `src/myapp/server/<feature>_store.clj` | SQL / `d/transact` lives here, nowhere else  |
| cross-platform feature  | `src/myapp/<feature>.cljc`          | `definent` + `defscreen`, with reader conditionals |
| boot wiring             | `src/myapp/server/main.clj`         | `:require` the new ns + preload state             |
| nav                     | `src/myapp/screens.cljc`            | link to the new screen from somewhere visible     |

Anything that isn't one of these five is a smell -- you probably
wanted a generator or a refactor.

## 1. Persistence

For SQL backends, drop the next migration in alongside the existing
ones (the runner sorts lexically):

```sql
-- resources/migrations/0005_create_tasks.sql
CREATE TABLE tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,    -- BIGSERIAL on Postgres
  title TEXT NOT NULL,
  done INTEGER NOT NULL DEFAULT 0,         -- BOOLEAN on Postgres
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
)
```

For Datomic, create `resources/datomic/schema-tasks.edn`. The boot
runner reads every `.edn` file in that dir and transacts them as a
single batch:

```clojure
[{:db/ident :tasks/title       :db/valueType :db.type/string
  :db/cardinality :db.cardinality/one}
 {:db/ident :tasks/done        :db/valueType :db.type/boolean
  :db/cardinality :db.cardinality/one}
 {:db/ident :tasks/created-at  :db/valueType :db.type/instant
  :db/cardinality :db.cardinality/one}]
```

Restart the server. The migration runner logs `:migrate.apply :id
0005_create_tasks` (SQL) or transacts the schema silently (Datomic).
Re-runs are idempotent.

## 2. Server-only store

Mirror the existing `myapp.server.notes-store`. Keep this namespace
the **only** place that imports the JDBC / Datomic API -- the
feature ns shouldn't see SQL strings.

```clojure
;; src/myapp/server/tasks_store.clj
(ns myapp.server.tasks-store
  (:require [next.jdbc.sql   :as sql]
            [myapp.server.db :as db]))

(defn list-tasks []
  (->> (db/query ["SELECT id, title, done, created_at
                     FROM tasks ORDER BY id DESC LIMIT 100"])
       (mapv (fn [row]
               {:id         (:tasks/id row)
                :title      (:tasks/title row)
                :done?      (= 1 (:tasks/done row))
                :created-at (:tasks/created_at row)}))))

(defn add-task! [title]
  (sql/insert! (db/ds) :tasks {:title title}))
```

For Datomic, the same fns wrap `d/q` / `d/transact` -- look at
`myapp.server.notes-store` in the `--db datomic` scaffold for the
exact shape.

## 3. The cross-platform feature ns

The same `definent` runs on server (authoritatively, against the
DB) and on the client (optimistically, against the in-memory
predicted state). A reader conditional is what lets one form do
both:

```clojure
;; src/myapp/tasks.cljc
(ns myapp.tasks
  (:require [wun.intents :refer [definent]]
            [wun.screens :refer [defscreen]]
            #?(:clj [myapp.server.tasks-store :as store])))

(definent :myapp/add-task
  {:params [:map [:title [:string {:min 1}]]]
   :morph
   (fn [state {:keys [title]}]
     (if (nil? (:session state))
       (assoc state :auth-error "log in to add tasks")
       #?(:clj
          (do (store/add-task! title)
              (assoc state
                     :tasks      (store/list-tasks)
                     :auth-error nil))
          :cljs
          (update state :tasks (fnil conj [])
                  {:title title :id :optimistic :done? false}))))})

(defscreen :myapp/tasks
  {:path "/tasks"
   :render
   (fn [state]
     [:wun/Stack {:gap 16 :padding 24}
      [:wun/Heading {:level 1} "Tasks"]
      (if (:session state)
        [:wun/Form {:on-submit {:intent :myapp/add-task
                                :params {:title :form/title}}}
         [:wun/Stack {:gap 8}
          [:wun/TextField {:name "title" :placeholder "what to do?"}]
          [:wun/Button {:type :submit} "Add"]]]
        [:wun/Stack {:gap 8}
         [:wun/Text {:variant :body} "Log in to add tasks."]
         [:wun/Button {:on-press {:intent :wun/navigate :params {:path "/login"}}}
          "→ Log in"]])
      [:wun/Stack {:gap 4}
       (for [t (:tasks state [])]
         ^{:key (:id t)}
         [:wun/Stack {:direction :row :gap 8 :padding 8}
          [:wun/Text {:variant :body} (:title t)]
          (when (:done? t) [:wun/Text {:variant :caption} "✓"])])]
      [:wun/Stack {:direction :row :gap 8}
       [:wun/Button {:on-press {:intent :wun/navigate :params {:path "/"}}}
        "← Home"]]])})
```

A few details worth knowing:

- **The auth gate runs in two places.** The render fn hides the
  submit button for logged-out users; the morph also bails when
  `(:session state)` is nil. Render-side guards are a UX hint;
  the morph guard is the actual boundary against a hostile client
  that POSTs `/intent` directly. Don't skip either.
- **`:server-only?` is for intents whose morph genuinely cannot
  run on the client** (password verification, third-party API
  calls). `:myapp/add-task` is fine to run on both sides because
  the client branch just predicts the new row appearing -- the
  server's authoritative version overwrites it on the next SSE
  patch.
- **Optimistic IDs use `:optimistic`** as a sentinel value. When
  the server's `(list-tasks)` ships back, the optimistic entry
  is replaced by the real row (with a real numeric id) via the
  next SSE diff. The user sees one stable row; the optimistic
  flicker is invisible on a fast connection.

## 4. Boot wiring

Two edits to `src/myapp/server/main.clj`:

1. Add `myapp.server.tasks-store` and `myapp.tasks` to the
   `(:require ...)` block.
2. Preload the table into state alongside notes, so the screen's
   first render shows existing rows:

```clojure
(ns myapp.server.main
  (:require [wun.server.http :as http]
            wun.foundation.components
            myapp.server.db
            myapp.server.notes-store
            myapp.server.tasks-store          ;; new
            myapp.notes
            myapp.tasks                       ;; new
            myapp.server.auth
            myapp.auth
            [wun.server.state :as wun-state]
            myapp.components
            myapp.intents
            myapp.screens))

(defn -main [& _]
  (myapp.server.db/init!)
  (swap! wun-state/app-state assoc
         :notes (myapp.server.notes-store/list-notes)
         :tasks (myapp.server.tasks-store/list-tasks))   ;; new
  (myapp.server.auth/init!)
  (http/start! {:static "public"})
  @(promise))
```

Without the preload swap, `(:tasks state)` would be `nil` until the
first add-task fires; the screen would render an empty list (because
of the `(or [])` default) and users would think nothing's there.

## 5. Nav from home

Open `src/myapp/screens.cljc`. The `--db` overlay ships a hub-style
home with a "Try the features" section. Add one more button:

```clojure
[:wun/Button {:on-press {:intent :wun/navigate :params {:path "/tasks"}}}
 "→ Tasks (your todo list)"]
```

A user landing on `/` now sees a clear path to the new feature.

## 6. End-to-end test

```bash
wun dev
```

In a browser:

1. Open `http://localhost:8081`.
2. Click "Sign up", create an account.
3. Notice the nav now shows "logged in as ..." with a "log out"
   button.
4. Click "→ Tasks". The compose form is visible.
5. Submit "buy milk". The row appears immediately (optimistic),
   then again as the server's authoritative response replaces it
   with a real numeric `id`.
6. Hard-reload (Cmd-R / Ctrl-R). The row is still there because the
   server re-loaded it from the DB on the next SSE connect.

In a second terminal, peek at the row:

```bash
# sqlite
sqlite3 data/myapp.db 'SELECT * FROM tasks;'

# postgres (from inside the running app's compose project)
docker compose exec postgres psql -U myapp -d myapp -c 'SELECT * FROM tasks;'

# datomic (REPL)
clj
=> (require '[datomic.client.api :as d] '[myapp.server.db :as db])
=> (db/init!)
=> (d/q '[:find ?title :where [_ :tasks/title ?title]] (db/db))
```

7. In a private window (so localStorage isn't shared), open
   `http://localhost:8081/tasks` while logged out. The compose
   form is replaced by a "log in" CTA. Try POSTing the intent
   directly -- the morph's session guard bails:

```bash
curl -s -X POST http://localhost:8081/intent \
  -H 'Content-Type: application/json' \
  -d '{"intent":"myapp/add-task","params":{"title":"hostile"}}'
```

The state's `:auth-error` updates over SSE; no row gets written.

## 7. Ship it

```bash
docker compose up --build       # locally
fly deploy                       # to fly.io (after `fly launch`)
```

The healthcheck at `/healthz` and the structured `myapp.log`
output let your platform of choice know the app is alive.

## What you didn't have to do

The five-file shape skips a lot of plumbing common in other stacks.
You didn't write:

- a controller / route handler (the `definent` is the controller)
- a websocket / long-poll bridge (Wun's SSE handles it)
- separate validators for client and server (Malli runs both)
- separate JSON serialization for the wire (transit-json built in)
- a separate "send the new state to other clients" hook (the
  framework's diff broadcast does it for free)

Once the muscle memory's there, a CRUD feature is ~80 lines across
five files and a working web + native UI for free.

## See also

- `skills/add-a-database-feature.md` -- terse playbook version of
  this doc, suited for AI-agent ingestion.
- `skills/wire-an-intent.md` -- how an intent flows end-to-end
  without a DB layer underneath.
- `skills/deploy-with-docker.md` -- shipping the result.
- `docs/architecture/head-and-cache.md` -- why the optimistic
  morph round-trip works the way it does.
