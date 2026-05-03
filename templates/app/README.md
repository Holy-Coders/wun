# myapp

A Wun application scaffolded by `wun new app myapp`.

By default this app consumes Wun via **sibling local-root deps** -- it
expects the `wun` repository to be checked out next door (`../wun`). That's
the fastest path to a working dev loop and matches the brief's
"fork-the-monorepo" reality. Comments in each build file show how to
swap to remote refs (Git deps / SwiftPM URL / JitPack) once the framework
is stable enough that you'd rather pin to a tag than develop against HEAD.

If you want to **dogfood Wun** (edit it while building this app), the
CLI ships an editable-install workflow modeled on `npm link`:

```bash
cd /path/to/wun && wun link        # register the wun checkout
cd /path/to/myapp && wun link      # rewrite this app's deps.edn at it
wun doctor                          # verify everything resolves
wun unlink                          # restore deps.edn when you're done
```

Re-running `wun link` from a different wun checkout switches both the
CLI and any subsequently-linked apps to that checkout.

## Layout

```
myapp/
├── deps.edn               server + cljs deps; pulls wun-shared / wun-server / wun-web
├── shadow-cljs.edn        web build config
├── package.json           shadow-cljs is npm-launchable
├── public/index.html      web entry document
├── src/myapp/
│   ├── components.cljc    `:myapp/*` component declarations
│   ├── intents.cljc       intent morphs
│   ├── screens.cljc       defscreen entries
│   ├── server/main.clj    server entry point
│   └── web/main.cljs      web entry point + custom :myapp/* renderers
├── ios/                   SwiftPM package consuming wun-ios
└── android/               Gradle module consuming wun-android
```

## Develop

The Wun CLI's `dev` command works in any monorepo with the marker layout, so
either:

```bash
# from inside the myapp/ directory:
wun dev          # starts the Clojure server on :8080 and shadow-cljs on :8081
```

…or the equivalent raw commands:

```bash
clojure -M:server                            # server
npx shadow-cljs watch app                    # web
cd ios     && swift run myapp-demo           # iOS demo (requires the wun repo too)
cd android && gradle run                     # Compose Desktop demo
```

Open <http://localhost:8081> for the web client.

## Add a screen / component / intent

```bash
wun add screen    myapp/profile
wun add intent    myapp/log-in
wun add component myapp/Card
```

These run from inside this app dir; the CLI walks up the tree to find the
wun-server marker. (Note: `wun add` currently writes into the *wun* repo's
example pack rather than your app -- copy the generated stubs into
`src/myapp/` here and into `ios/Sources/` / `android/src/main/kotlin/myapp/`.)

## Switch to remote refs

When you want to stop pulling Wun from a sibling clone:

1. **Clojure** -- in `deps.edn`, replace `:local/root` entries with:
   ```clojure
   wun/wun-shared {:git/url "https://github.com/Holy-Coders/wun.git"
                   :git/tag "v0.1.0" :git/sha "<full-sha>"
                   :deps/root "wun-shared"}
   ```
2. **iOS** -- in `ios/Package.swift`, replace `.package(path: ...)` with
   `.package(url: "https://github.com/Holy-Coders/wun.git", from: "0.1.0")`.
3. **Android** -- in `android/settings.gradle.kts`, replace
   `includeBuild("../../wun/wun-android")` with a JitPack dependency
   in `build.gradle.kts`:
   ```kotlin
   repositories { maven("https://jitpack.io") }
   dependencies {
       implementation("com.github.Holy-Coders.wun:wun-android:0.1.0")
   }
   ```

`wun release v0.1.0` from the wun repo tags + pushes a release suitable
for any of the above forms.
