(ns ongakuka.route-test
  (:require [kotoba.lang.text] [clojure.test :refer [deftest is testing]]
            [ongakuka.route :as route]
            [ongakuka.view :as view]))

(deftest dispatch-page-and-health
  (is (= :page (:action (route/dispatch "GET" "/"))))
  (is (= :health (:action (route/dispatch "GET" "/health"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/health"))))
  (is (= :not-found (:action (route/dispatch "GET" "/nope")))))

(deftest dispatch-xrpc
  (testing "単一セグメントの nsid だけ通す"
    (is (= {:action :xrpc :nsid "com.etzhayyim.ongakuka.listTracks"}
           (route/dispatch "POST" "/xrpc/com.etzhayyim.ongakuka.listTracks"))))
  (testing "空だけが 400。多段は移行前と同じく転送する（絞るのは方針変更）"
    (is (= :bad-request (:action (route/dispatch "POST" "/xrpc/"))))
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a/b"))))
  (testing "preflight と method"
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/x"))))
    (is (= :method-not-allowed (:action (route/dispatch "GET" "/xrpc/x"))))))

(deftest mcp-url-resolution
  (is (= "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
         (route/mcp-router-url {})))
  (is (= "https://a.example/x" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "https://a.example/x/"})))
  (testing "空白だけの設定は未設定として扱う"
    (is (= "https://b.example" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "   "
                                                     :MCP_ROUTER_URL "https://b.example"})))))

(deftest relay-headers-forwards-what-it-received
  (testing "移行前は host を削るだけで、authorization も上流へ届いていた"
    (let [h (route/relay-headers [["Host" "x.example"]
                                  ["Authorization" "Bearer t"]
                                  ["Content-Length" "9"]
                                  ["X-Trace" "abc"]]
                                 "com.a.b")]
      (is (= "Bearer t" (get h "authorization"))
          "authorization が落ちている —— preflight はこれを許可すると言っている")
      (is (= "abc" (get h "x-trace")))
      (is (nil? (get h "host")) "host は宛先が変わるので渡さない")
      (is (nil? (get h "content-length")) "body を詰め直すので元の長さは嘘になる")
      (is (= "com.a.b" (get h "x-etzhayyim-xrpc-method"))))))

(deftest unwrap
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:structuredContent {:a 1}}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:a 1}})))
  (is (false? (:ok? (route/unwrap-mcp {:error {:message "boom"}})))))

(deftest page-carries-no-unrendered-markup
  (testing "Markdown の記法はページ本文で何も意味しない —— そのまま画面に出る"
    ;; 実測 2026-08-19（cloud-itonami/aidesk の agent が指摘）: この view は
    ;; docstring の書き癖のまま本文にも ** を書いており、描画されたページに
    ;; literal な ** が 2 個出ていた。強調は [:strong] で書く。
    (let [html (view/render {:css "" :routes route/routes
                             :vars [:APP_NANOID] :mcp-url "https://x.invalid/y"})]
      (is (zero? (count (re-seq #"\*\*" html)))
          "ページ本文に Markdown の ** が残っている")
      (is (zero? (count (re-seq #"(?m)^#{1,6} " html)))
          "ページ本文に Markdown の見出し記法が残っている"))))

(deftest page-shows-the-real-routes
  (testing "ページは route 表から描く。0 を焼かない（docs/adr/0001 の欠陥）"
    (let [html (view/render {:css "/*x*/" :routes route/routes
                             :vars [:APP_NANOID :APP_UI_TYPE]
                             :mcp-url "https://mcp.example/x"})]
      (doseq [r route/routes]
        (is (kotoba.lang.text/includes? html (:route/path r))
            (str (:route/path r) " がページに出ていない")))
      (is (kotoba.lang.text/includes? html "APP_NANOID"))
      (is (kotoba.lang.text/includes? html "https://mcp.example/x"))
      (is (not (kotoba.lang.text/includes? html "No public route is declared"))))))
