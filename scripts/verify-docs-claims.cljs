#!/usr/bin/env nbb
;; verify-docs-claims — re-derive every number README.md and docs/operator-quickstart.md
;; state, from the tree itself, and fail when the tree and the prose disagree.
;;
;; Before the cljs migration this file's load-bearing claim was a GAP: the Worker
;; that would be deployed was a SvelteKit build output while src/app.ts -- the file
;; that read like the application -- was in no bundle. That gap is closed, so the
;; claims now assert the CLOSURE, and they are written so the gap cannot quietly
;; come back: the TypeScript is asserted ABSENT by name, not merely absent from a
;; byte total.
;;
;; Usage:  nbb scripts/verify-docs-claims.cljs [<dir>]     (<dir> FIRST, default ".")
;; Exit:   0 every claim holds · 1 a claim is false · 2 could not answer

(require '["node:fs" :as fs]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '[kotoba.lang.text :as str])

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))
(def APP "appview/etzhayyim-wasm-ongakuka-0ng4k4k4")

(def claims
  {:tracked-files 21
   :inherited-bytes 6675           ; the 6 inherited files still carried unchanged
   :svelte-artifacts 0             ; no .svelte / svelte.config / svelte-dir file survives
   :sveltekit-compat-flags 0       ; nodejs_compat / nodejs_als were adapter-cloudflare's
   :production-ts-files 0
   :production-canonical-files 4
   :declared-vars 8
   :declared-routes 2
   :wrangler-main "../../dist/worker.js"
   :shadow-output-dir "dist"
   :shadow-export "ongakuka.worker/handler"})

;; Inherited files this repository still carries BYTE-IDENTICAL. wrangler.jsonc left
;; this set deliberately in the migration and is checked by content below instead.
;; CLAUDE.md left this set deliberately: it described the runtime as "TS Native
;; (src/app.ts)" and the frontend as "Hono router + Svelte CSR", both of which the
;; migration made false. Checked by content below instead of by hash.
(def preserved
  {"MIGRATION-TODO.md" "793d1da2f465cd692205ebc1d8b38cc6c50502b525226255f74d9a858cd6761f"
   "NOTICE" "bae68743feb911cbedcc745b136e444d3595854e4324f59e7cc9438ccda13d49"
   "PROJECT.jsonld" "d9c248b581dd52518c8e5d43f1f0078890a1e9f768f23111153fc7849e58ca58"
   "README.edn" "8a03a25c86d9e7f052aa476acadb7c701f3dc35d4466b014caec51d9abe8259c"
   "migration.edn" "3a8357a9f5594a5d34abdc5b522f92ad49799662d123012499defc57c247be0c"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/kotodama.jsonld" "8b185910fb1c3fd1bd6e2ed9b6d0dee64f4c6b74d0ff4c0694cb944419c21e07"})

;; What the migration REMOVED, by name. A byte total cannot say "the TypeScript is
;; gone"; this can, and it fails if any of it comes back.
(def removed-by-migration
  ["appview/etzhayyim-wasm-ongakuka-0ng4k4k4/src/app.ts"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/package.json"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/src/app.html"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/src/routes/+page.svelte"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/src/routes/xrpc/[...path]/+server.ts"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/svelte.config.js"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/tsconfig.json"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/vite.config.ts"])

(def undetermined (atom []))
(def failures (atom []))
(defn undet! [m] (swap! undetermined conj m))

(defn tracked-files []
  (try (->> (.execSync cp "git ls-files" #js {:cwd root :encoding "utf8"})
            str/split-lines (remove str/blank?) vec)
       (catch :default e (undet! (str "git ls-files failed: " (.-message e))) nil)))
(defn slurp* [rel] (try (.readFileSync fs (str root "/" rel) "utf8") (catch :default _ nil)))
(defn bytes-of [rel] (try (.-size (.statSync fs (str root "/" rel))) (catch :default _ nil)))
(defn sha256 [rel]
  (try (-> (.createHash crypto "sha256") (.update (.readFileSync fs (str root "/" rel))) (.digest "hex"))
       (catch :default _ nil)))
(defn strip-jsonc [s] (str/replace s #"(?m)^\s*//.*$" ""))

(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" (name label)
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))
    ok))

(let [files (tracked-files)]
  (when (nil? files) (println "UNDETERMINED\tcould not list tracked files") (js/process.exit 2))
  (println (str "SCANNED\t" (count files)))
  (when (zero? (count files)) (println "UNDETERMINED\tscanned 0 files") (js/process.exit 2))

  (let [sizes (into {} (map (juxt identity bytes-of)) files)]
    (when-let [bad (seq (keep (fn [[f s]] (when (nil? s) f)) sizes))]
      (undet! (str "tracked but unreadable: " (str/join ", " bad))))

    (check! :tracked-files (:tracked-files claims) (count files))
    (check! :inherited-bytes (:inherited-bytes claims)
            (reduce + 0 (keep #(get sizes %) (keys preserved))))
    (check! :preserved-files-unchanged []
            (vec (keep (fn [[f want]] (let [got (sha256 f)]
                                        (when-not (= want got) (str f " " (or got "MISSING")))))
                       preserved)))

    ;; the TypeScript is gone, by name
    (check! :removed-by-migration-absent []
            (vec (filter #(some? (bytes-of %)) removed-by-migration)))

    ;; Svelte is gone and must not come back. The removed-by-migration list names
    ;; the eight files; these two catch a return under ANY name -- a new .svelte
    ;; file, a svelte.config, a svelte/ directory, or the compat flags that only
    ;; adapter-cloudflare needed.
    (check! :svelte-artifacts (:svelte-artifacts claims)
            (count (filter #(or (str/ends-with? % ".svelte")
                                (str/includes? % "svelte.config")
                                (str/includes? % "/svelte/"))
                           files)))

    ;; CLAUDE.md no longer plans a Svelte frontend or claims a TypeScript runtime
    (let [c (slurp* "CLAUDE.md")]
      (if (nil? c)
        (undet! "CLAUDE.md unreadable")
        (check! :claude-md-describes-cljs true
                (and (not (str/includes? c "Svelte CSR"))
                     (not (str/includes? c "App Component (TS Native)"))
                     (str/includes? c "shadow-cljs")))))

    ;; language of the production source, the two numbers the owner's correction added
    (let [prod (remove #(str/starts-with? % "scripts/") files)]
      (check! :production-ts-files (:production-ts-files claims)
              (count (filter #(str/ends-with? % ".ts") prod)))
      (check! :production-canonical-files (:production-canonical-files claims)
              (count (filter #(re-find #"\.(cljs|cljc|clj|kotoba)$" %) prod))))

    ;; the deployed bundle is built from the source in this tree
    (let [w (some-> (slurp* (str APP "/wrangler.jsonc")) strip-jsonc)
          sh (slurp* "shadow-cljs.edn")]
      (if (or (nil? w) (nil? sh))
        (undet! "wrangler.jsonc or shadow-cljs.edn unreadable")
        (let [j (js->clj (.parse js/JSON w) :keywordize-keys false)]
          (check! :wrangler-main (:wrangler-main claims) (get j "main"))
          (check! :declared-vars (:declared-vars claims) (count (get j "vars")))
          (check! :declared-routes (:declared-routes claims) (count (get j "routes")))
          ;; the old config served a SvelteKit client dir that no longer exists
          (check! :no-stale-assets-binding true (nil? (get j "assets")))
          (check! :sveltekit-compat-flags (:sveltekit-compat-flags claims)
                  (count (filter #{"nodejs_compat" "nodejs_als"}
                                 (or (get j "compatibility_flags") []))))
          (check! :shadow-builds-that-main true
                  (and (str/includes? sh (str ":output-dir \"" (:shadow-output-dir claims) "\""))
                       (str/includes? sh (:shadow-export claims))
                       (str/includes? (get j "main") (str (:shadow-output-dir claims) "/worker.js")))))))

    ;; The page renders the route TABLE rather than a baked count -- the defect
    ;; ADR-0001 recorded was a literal `routeCount: 0` beside a config declaring
    ;; two. Asserted structurally (the view takes :routes, the worker passes the
    ;; real table) and NOT by forbidding a substring: the first version of this
    ;; check forbade "routeCount" anywhere and was tripped by the docstring that
    ;; explains the old defect. A check a comment can fail is a check about prose.
    (let [v (slurp* "src/ongakuka/view.cljc")
          w (slurp* "src/ongakuka/worker.cljs")]
      (if (or (nil? v) (nil? w))
        (undet! "view.cljc or worker.cljs unreadable")
        (check! :page-renders-route-table true
                (and (str/includes? v "[{:keys [routes vars mcp-url built-at]}]")
                     (str/includes? v "(route-rows routes)")
                     (str/includes? w ":routes route/routes")))))))

(let [u @undetermined f @failures]
  (when (seq u)
    (doseq [m u] (println (str "UNDETERMINED\t" m)))
    (println "Refusing to report a pass: the tree could not be read completely.")
    (js/process.exit 2))
  (if (seq f)
    (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f)))) (js/process.exit 1))
    (do (println "OK\tevery claim in README.md and docs/operator-quickstart.md holds") (js/process.exit 0))))
