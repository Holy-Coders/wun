# Add a DB-backed feature to a Wun app

## When to use this

The user wants a screen that reads/writes a database table -- their
own equivalent of the demo `:myapp/notes` feature shipped with
`wun new app foo --db <db>`. They have an app already scaffolded
with a database (any of sqlite, postgres, datomic).

If the user does NOT yet have a database wired in, send them to
`wun new app foo --db <choice>` first; this skill assumes
`myapp.server.db` and `myapp.server.notes-store` already exist.

## Inputs you need

- The feature name (e.g. `tasks`, `posts`, `comments`).
- The columns / attributes the user wants on the entity.
- Their DB backend (`sqlite | postgres | datomic`) -- check
  `deps.edn` if unsure.

## Steps

### 1. Migration / schema (the persistent shape)

For SQL backends, create `resources/migrations/<NNNN>_<feature>.sql`
where `<NNNN>` is the next free sequence number after the existing
migrations:

```sql
-- resources/migrations/0005_create_tasks.sql
CREATE TABLE tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,   -- BIGSERIAL on Postgres
  title TEXT NOT NULL,
  done INTEGER NOT NULL DEFAULT 0,        -- BOOLEAN on Postgres
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
)
```

For Datomic, append to or create a new file under
`resources/datomic/schema-<feature>.edn` (`db.clj` reads every
`.edn` file in that dir):

```clojure
;; resources/datomic/schema-tasks.edn
[{:db/ident :tasks/title :db/valueType :db.type/string
  :db/cardinality :db.cardinality/one}
 {:db/ident :tasks/done  :db/valueType :db.type/boolean
  :db/cardinality :db.cardinality/one}
 {:db/ident :tasks/created-at :db/valueType :db.type/instant
  :db/cardinality :db.cardinality/one}]
```

The runner picks new files up on the next server boot.

### 2. Store namespace (the server-only data layer)

Mirror the existing `myapp.server.notes-store`:
`src/myapp/server/tasks_store.clj`. Expose `(list-tasks)` and
`(add-task! ...)` (and however many other ops you need). Every fn
calls into `(myapp.server.db/ds)` (SQL) or `(myapp.server.db/conn)`
(Datomic) -- the SQL you write here is the only place the backend
shows through.

### 3. Cross-platform feature ns (`.cljc`)

Mirror `src/myapp/notes.cljc`: a `defintent` that delegates to the
store via a `#?(:clj ...)` reader conditional, and a `defscreen`
that renders `(:tasks state)`:

```clojure
(ns myapp.tasks
  (:require [wun.intents :refer [defintent]]
            [wun.screens :refer [defscreen]]
            #?(:clj [myapp.server.tasks-store :as store])))

(defintent :myapp/add-task
  {:params [:map [:title [:string {:min 1}]]]
   :morph (fn [state {:keys [title]}]
            #?(:clj  (do (store/add-task! title)
                         (assoc state :tasks (store/list-tasks)))
               :cljs (update state :tasks (fnil conj [])
                             {:title title :id :optimistic})))})

(defscreen :myapp/tasks
  {:path "/tasks"
   :render (fn [state] [:wun/Stack ...])})
```

### 4. Wire into `server/main.clj`

Add the new ns to the `(:require ...)` block AND add a preload
swap so the screen's first render sees populated data:

```clojure
(ns myapp.server.main
  (:require ...
            myapp.server.tasks-store
            myapp.tasks))

(defn -main [& _]
  (myapp.server.db/init!)
  (swap! wun-state/app-state assoc
         :notes (myapp.server.notes-store/list-notes)
         :tasks (myapp.server.tasks-store/list-tasks))   ;; <-- add this
  (myapp.server.auth/init!)
  (http/start! {:static "public"})
  @(promise))
```

### 5. (Optional) Link from another screen

Add a navigation button somewhere visible:

```clojure
[:wun/Button {:on-press {:intent :wun/navigate :params {:path "/tasks"}}}
 "→ Tasks"]
```

## Verify

```bash
wun dev
```

- Open `http://localhost:8081/tasks`. Submit the form. The new row
  should appear immediately (optimistic) and stay after a hard reload
  (authoritative).
- Inspect the row:
  - SQLite:    `sqlite3 data/myapp.db 'SELECT * FROM tasks;'`
  - Postgres:  `psql $DATABASE_URL -c 'SELECT * FROM tasks;'`
  - Datomic:   `(d/q '[:find ?t ?title :where [?t :tasks/title ?title]] (db))`

## Common gotchas

- **Don't put store calls inside the `:cljs` branch.** The store
  namespace doesn't exist on the client; the cljs build will fail.
- **State preload runs once, at boot.** If your morph re-reads the
  table on every write (the pattern above), you don't need
  per-connection refreshes. If you skip the re-read, all clients see
  stale data until the next preload.
- **Migrations are forward-only.** Don't edit a migration file once
  it's been applied to a real DB -- write a new one to change it.
