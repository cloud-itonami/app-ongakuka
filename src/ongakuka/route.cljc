(ns ongakuka.route
  "Which handler answers a request — as data, decided by a pure function.

  This is `.cljc` and not `.cljs` on purpose. Routing is the part of an edge
  worker that is worth testing, and it is testable here without a browser, a
  build, or a network. `ongakuka.worker` is the only namespace that touches
  Request/Response, and it does nothing this file has not already decided.

  It is also the first thing that should move to `.kotoba` once the ingress
  capability qualifies (`:native-aot`/`:wasm-aot` are pending today —
  ADR-2606290000): a route table is a decision over scalars and strings,
  which is exactly the shape that survives that move."
  (:require [clojure.string :as str]))

(def routes
  "The public surface, as data. The landing page renders THIS, so a route that
  exists and a route the page advertises cannot drift apart — the defect
  docs/adr/0001 recorded was a page that said `Routes 0` beside a config
  declaring two."
  [{:route/path "/"          :route/method :get  :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/health"    :route/method :get  :route/kind :json
    :route/doc "生存確認。デプロイされた面が答えることを外から確かめられる"}
   {:route/path "/xrpc/:nsid" :route/method :post :route/kind :proxy
    :route/doc "XRPC を MCP router へ中継する"}])

(defn- xrpc-nsid
  "`/xrpc/<nsid>` の nsid。空文字と多段パスは nil（誤って前方一致で通さない）。"
  [path]
  (when (str/starts-with? path "/xrpc/")
    (let [rest' (subs path (count "/xrpc/"))]
      (when (and (seq rest') (not (str/includes? rest' "/")))
        rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  返すのは `{:action …}` で、`:action` は
  `:page` / `:health` / `:xrpc` / `:cors-preflight` / `:not-found` /
  `:method-not-allowed` / `:bad-request` のいずれか。"
  [method path]
  (let [m (keyword (str/lower-case (or method "get")))]
    (cond
      (and (= m :options) (str/starts-with? (or path "") "/xrpc/"))
      {:action :cors-preflight}

      (str/starts-with? (or path "") "/xrpc/")
      (if (= m :post)
        (if-let [nsid (xrpc-nsid path)]
          {:action :xrpc :nsid nsid}
          {:action :bad-request :reason "XRPC method is missing or not a single segment"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= path "/health") (if (= m :get)
                           {:action :health}
                           {:action :method-not-allowed :allow "GET"})
      (= path "/")       (if (= m :get)
                           {:action :page}
                           {:action :method-not-allowed :allow "GET"})
      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → MCP router の URL。末尾スラッシュは落とす。

  既定値をここに焼くのは、設定が無いときに黙って何処かへ POST しないためで
  はなく、**どこへ行くのかを 1 箇所で読めるようにする**ため。呼び出し側は
  この戻り値をそのまま使う。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")
        (str/replace #"/+$" ""))))

(defn unwrap-mcp
  "MCP router の応答から、呼び手に返す値を取り出す。

  `{:result {:structuredContent X}}` → X、`{:result X}` → X、それ以外は素通し。
  `{:error …}` は呼び出し側が 502 にするので、ここでは判定だけ返す。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    (and (map? payload) (contains? payload :result))
    (let [r (:result payload)]
      {:ok? true :value (if (and (map? r) (contains? r :structuredContent))
                          (:structuredContent r)
                          r)})

    :else {:ok? true :value payload}))
