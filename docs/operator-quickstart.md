# operator-quickstart

**この repo で今日実際にできることを、踏める形で上から書く。** 所要 5 分。
Cloudflare のアカウントは要らない（deploy だけが要る。§5）。

出力はすべて実際に walk した結果である。

## 0. 前提

| 要るもの | 確認 | この walk で使った版 |
|---|---|---|
| git | `git --version` | 2.51.0 |
| nbb | `npx --yes nbb --version` | v1.4.208 |
| clojure | `clojure --version` | ビルド時のみ |

## 1. 取得して、書いてあることが本当か検査する

```bash
git clone git@github.com:cloud-itonami/app-ongakuka.git
cd app-ongakuka
REPO=$PWD
npx --yes nbb scripts/verify-docs-claims.cljs .
```

末尾が `OK` なら README の数値・存在・不在は tree と一致している。
**exit 2（UNDETERMINED）は 0 ではない** —— tree を読み切れなかったという別の
答えで、「検査して問題なし」と混ぜない。

この検査には移行の不変条件が入っている: TypeScript が戻っていないこと（撤去した
8 パスの不在 + `.ts` の総数）、`wrangler.jsonc` の `main` が shadow の出力先を
指していること、ページが route 表から描かれていること。

## 2. テストを走らせる（ビルド不要・ブラウザ不要）

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので、nbb だけで回る。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:test:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/run.cljs <<'EOF'
(require '[cljs.test :refer [run-tests]] 'ongakuka.route-test)
(run-tests 'ongakuka.route-test)
EOF
npx --yes nbb --classpath "$CP" /tmp/run.cljs
```

実際の出力:

```
Testing ongakuka.route-test

Ran 5 tests containing 21 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` は**単一セグメントの nsid だけ**通す（`/xrpc/` と
`/xrpc/a/b` は 400。前方一致で素通ししない）、MCP router の URL 解決（空白だけの
設定は未設定として扱う）、`result` / `structuredContent` の剥がし方、そして
**ページが route 表から描かれること**（固定値を焼いていたら落ちる）。

## 3. ページを描画して採点する

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
CP="src:$K/jp-go-digital-design-system/src:$K/html/src:$K/css/src"
cat > /tmp/render.cljs <<'EOF'
(require '["node:fs" :as fs] '[ongakuka.view :as view] '[ongakuka.route :as route])
(let [css (.readFileSync fs (str (.-DDS js/process.env) "/resources/jp_go_dds/dds.css") "utf8")]
  (.writeFileSync fs "/tmp/ong-page.html"
    (view/render {:css css :routes route/routes
                  :vars [:APP_NANOID :APP_UI_TYPE]
                  :mcp-url "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"}))
  (println "ok"))
EOF
DDS="$K/jp-go-digital-design-system" npx --yes nbb --classpath "$CP" /tmp/render.cljs

cd $K/design-quality && npx --yes nbb -m design-quality.cli score /tmp/ong-page.html --min 95
```

実際の出力（末尾）:

```
  100.00  /tmp/ong-page.html
aggregate: 100.00
gate: aggregate 100.00 >= min 95.00 -> PASS
```

## 4. bundle をビルドする

**高負荷ビルドは同時 1 本に制限されている**（superproject `CLAUDE.md` の
resource governor）。直接叩かず、必ず guard 経由で:

```bash
cd "$REPO"
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx shadow-cljs release worker
ls -la dist/worker.js
```

lock を他セッションが持っていると exit 2 で拒否される。**迂回しない** ——
`resource-guard: build is already running (pid=…)` はエラーではなく順番待ちで
ある。

実際の出力（末尾）:

```
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 44.90s)
```

## 4.5 ビルドした成果物を実際に叩く

ここが deploy されるものに触る唯一の検査である。

```bash
cd "$REPO" && npx --yes nbb scripts/smoke-worker.cljs dist/worker.js
```

```
PASS	default export has fetch	expected=true	actual=true
PASS	GET / status	expected=200	actual=200
...
PASS	wrong method	expected=405	actual=405
OK	the built bundle answers as the route table says
```

**bundle が無ければ exit 2**（「判定できなかった」であって合格ではない）。

## 5. deploy

```bash
cd "$REPO/appview/etzhayyim-wasm-ongakuka-0ng4k4k4"
npx wrangler deploy
```

**ただし route が指すホストは解決しない**（`ongakuka.etzhayyim.com` /
`ong4k4k4.etzhayyim.com` とも NXDOMAIN）。deploy が成功しても誰も到達できない。
`/xrpc/` の中継先 `mcp.etzhayyim.com` も同様なので、到達できたとしても中継は
**502 を返す**（成功と同じ形で隠さない）。

superproject の deploy guard は `origin/main` を含む checkout からの deploy しか
許さない点も併せて注意。

## 6. ここに無いもの

- `compose` / `/blobs/:key` / `listTracks` —— 移行前の `src/app.ts` にあり、
  どこにも deploy されていなかった経路。宛先が NXDOMAIN、または binding が
  `wrangler.jsonc` に無いので**持ち越していない**（README の「持ち越さなかった
  もの」）
- 生成そのもの（murakumo の audio 推論側にある）
- `MIGRATION-TODO.md` の 7 項目の憲章適合レビュー
