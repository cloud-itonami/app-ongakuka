#!/usr/bin/env nbb
;; verify-docs-claims — re-derive every number README.md and docs/operator-quickstart.md
;; state, from the tree itself, and fail when the tree and the prose disagree.
;;
;; The load-bearing claims here are about a GAP: the Worker that would be deployed
;; is a SvelteKit build output, while src/app.ts -- the file that reads like the
;; application -- is referenced by nothing and declared by no package.json. Prose
;; cannot notice when that stops being true. This can.
;;
;; Usage:  nbb scripts/verify-docs-claims.cljs [<dir>]     (<dir> FIRST, default ".")
;; Exit:   0 every claim holds · 1 a claim is false · 2 could not answer
;;
;; Exit 2 is not exit 0. A run that cannot read the tree reports UNDETERMINED and
;; exits 2, so "not measured" can never be mistaken for "measured and clean".

(require '["node:fs" :as fs]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '[clojure.string :as str])

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))
(def APP "appview/etzhayyim-wasm-ongakuka-0ng4k4k4")

;; ── the claims, as data ──────────────────────────────────────────────────────
;; Measured 2026-08-18 against cloud-itonami/app-ongakuka 27cec35, and the source
;; tree etzhayyim/root 0426d779 that migration.edn names.
(def claims
  {:tracked-files        20
   :inherited-bytes      29888     ; the 16 files that came out of the extraction
   :source-bytes         29319     ; the 14 files migration.edn declares
   :added-after-extraction #{"README.edn" "migration.edn"}   ; == migration.edn :allowed-additions
   :added-by-this-round  #{"README.md"
                           "docs/adr/0001-the-deployed-worker-is-not-the-file-you-open.edn"
                           "docs/operator-quickstart.md"
                           "scripts/verify-docs-claims.cljs"}
   :wrangler-main        "svelte/.svelte-kit/cloudflare/_worker.js"
   :declared-vars        8
   :declared-routes      2
   :page-route-count     0         ; what the landing page tells a visitor
   :page-vars-count      0
   :app-ts-bytes         5460
   :refs-to-app-ts       0         ; from anywhere under svelte/
   :packages-declaring-host-sdk 0  ; package.json files declaring @etzhayyim/kotodama-host-sdk
   :lockfiles            0})

(def preserved
  {"CLAUDE.md" "ae633f76c2a06ec9cd5947967fecd9015e23c91fdcff250494e7f7aea54e1797"
   "MIGRATION-TODO.md" "793d1da2f465cd692205ebc1d8b38cc6c50502b525226255f74d9a858cd6761f"
   "NOTICE" "bae68743feb911cbedcc745b136e444d3595854e4324f59e7cc9438ccda13d49"
   "PROJECT.jsonld" "d9c248b581dd52518c8e5d43f1f0078890a1e9f768f23111153fc7849e58ca58"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/kotodama.jsonld" "8b185910fb1c3fd1bd6e2ed9b6d0dee64f4c6b74d0ff4c0694cb944419c21e07"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/src/app.ts" "ef849e812070c10a7f3903a849866bfdcc82d1b734fce33030378d2cb81c7844"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/package.json" "6a9fd08c9b9987f099931cbdc21cbb7eb8b725872b7271ff749cd6b67e8021a9"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/src/app.html" "5d5c6f7836784ea8562af38e6dae5e5b8eb7668317b4d4edd4d2c85d4ed6d41d"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/src/routes/+page.svelte" "84cb7a7c36f12dc2032abf011ec32e9fb742ccab50ca911a508211285565ec87"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/src/routes/xrpc/[...path]/+server.ts" "6ff76c9ca77e0431ade2c33cc7b24a71de0791a5ca41ab486795dc5e0c017407"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/svelte.config.js" "b30807ad580712eb9d42fe8f2a3a23ddebb951075dbd06139d63fa5a232333dc"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/tsconfig.json" "d8a3f998af73617b07026a41adfbf33f7004647bc4a6759c00d7cc8b06c29e2a"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte/vite.config.ts" "b2e81bb7415499d7d6b5612166d29d0a9df5f0e7d2300eae5b8237c336c84c09"
   "appview/etzhayyim-wasm-ongakuka-0ng4k4k4/wrangler.jsonc" "4d7bfd980a3444034121b353fc1ec40325460a1d8d5bfdd4e4f5c1d4c962cf83"})

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

  (let [inherited (into (vec (keys preserved)) ["README.edn" "migration.edn"])
        sizes (into {} (map (juxt identity bytes-of)) files)
        missing (keep (fn [[f s]] (when (nil? s) f)) sizes)]
    (when (seq missing) (undet! (str "tracked but unreadable: " (str/join ", " missing))))

    (check! :tracked-files (:tracked-files claims) (count files))
    (check! :inherited-bytes (:inherited-bytes claims)
            (reduce + 0 (keep #(get sizes %) inherited)))
    (check! :source-bytes (:source-bytes claims)
            (reduce + 0 (keep #(get sizes %) (keys preserved))))
    (check! :preserved-files-unchanged []
            (vec (keep (fn [[f want]] (let [got (sha256 f)]
                                        (when-not (= want got) (str f " " (or got "MISSING")))))
                       preserved)))
    (check! :added-after-extraction (:added-after-extraction claims)
            (set (remove (into (set (keys preserved)) (:added-by-this-round claims)) files)))
    (check! :added-by-this-round (:added-by-this-round claims)
            (set (remove (into (set (keys preserved)) (:added-after-extraction claims)) files)))

    ;; migration.edn's own custody contract must still name exactly those additions
    (let [m (slurp* "migration.edn")]
      (if (nil? m)
        (undet! "migration.edn unreadable")
        (check! :allowed-additions-match true
                (every? #(str/includes? m (str "\"" % "\"")) (:added-after-extraction claims)))))

    ;; ── the gap: what would be deployed vs what a reader opens ──────────────
    (let [w (some-> (slurp* (str APP "/wrangler.jsonc")) strip-jsonc)]
      (if (nil? w)
        (undet! "wrangler.jsonc unreadable")
        (let [j (js->clj (.parse js/JSON w) :keywordize-keys false)]
          (check! :wrangler-main (:wrangler-main claims) (get j "main"))
          (check! :wrangler-main-absent true (nil? (bytes-of (str APP "/" (get j "main")))))
          (check! :declared-vars (:declared-vars claims) (count (get j "vars")))
          (check! :declared-routes (:declared-routes claims) (count (get j "routes"))))))

    ;; the landing page tells a visitor there are none of either
    (let [p (slurp* (str APP "/svelte/src/routes/+page.svelte"))]
      (if (nil? p)
        (undet! "+page.svelte unreadable")
        (do (check! :page-route-count (:page-route-count claims)
                    (some-> (re-find #"\"routeCount\":\s*(\d+)" p) second js/parseInt))
            (check! :page-says-no-routes true
                    (str/includes? p "No public route is declared next to this app surface"))
            (check! :page-says-no-vars true
                    (str/includes? p "No public vars are declared in the nearest wrangler config")))))

    ;; src/app.ts is unreachable: nothing under svelte/ names it, and no package.json
    ;; declares the SDK it imports
    (check! :app-ts-bytes (:app-ts-bytes claims) (get sizes (str APP "/src/app.ts")))
    (check! :refs-to-app-ts (:refs-to-app-ts claims)
            (reduce + 0 (for [f files
                              :when (str/starts-with? f (str APP "/svelte/"))
                              :let [t (slurp* f)] :when t]
                          (count (re-seq #"app\.ts|kotodama-host-sdk" t)))))
    (check! :packages-declaring-host-sdk (:packages-declaring-host-sdk claims)
            (count (for [f files
                         :when (str/ends-with? f "package.json")
                         :let [t (slurp* f)]
                         :when (and t (str/includes? t "kotodama-host-sdk"))]
                     f)))
    (check! :lockfiles (:lockfiles claims)
            (count (filter #(re-find #"(?i)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml)$" %) files)))))

(let [u @undetermined f @failures]
  (when (seq u)
    (doseq [m u] (println (str "UNDETERMINED\t" m)))
    (println "Refusing to report a pass: the tree could not be read completely.")
    (js/process.exit 2))
  (if (seq f)
    (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f)))) (js/process.exit 1))
    (do (println "OK\tevery claim in README.md and docs/operator-quickstart.md holds") (js/process.exit 0))))
