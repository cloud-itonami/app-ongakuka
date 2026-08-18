# app-ongakuka

**音楽家（ongakuka）—— 歌詞と style prompt から曲を生成する AI 音楽サービスの
appview。** 名前が機能を示さないので先に名乗る（この workspace の規約）。
`ongakuka` は「音楽家」であって、この repo が持つのは**その公開面（appview）**
である —— 生成そのものは murakumo の audio 推論側にあり、ここには無い。

`etzhayyim/root` の `60-apps/etzhayyim-project-ongakuka` からの抽出物である。
数字はすべて `scripts/verify-docs-claims.cljs` が tree から再計算して検査する。

## ⚠ 最初に読むこと — **deploy される handler は、あなたが開くファイルではない**

この repo で一番読み応えのあるファイルは
`appview/etzhayyim-wasm-ongakuka-0ng4k4k4/src/app.ts`（5,460 バイト）である。
`compose` コマンド、`/blobs/:key` の B2 配信、actor DID の登録、BPMN
dispatcher への proxy —— アプリケーションらしいものは全部ここに在る。

**そのファイルは、deploy される Worker に含まれない。**

| | |
|---|---|
| `wrangler.jsonc` の `main` | `svelte/.svelte-kit/cloudflare/_worker.js`（**SvelteKit のビルド出力**。tree に無い） |
| `svelte/` から `src/app.ts` への参照 | **0 件** |
| `@etzhayyim/kotodama-host-sdk`（app.ts の唯一の import）を宣言する package.json | **0 件** |
| lockfile | **0 件** |

`svelte/package.json` の依存は svelte 一式だけで、SDK も app.ts も入口が無い。
つまり **app.ts はこの repo のどのツールチェーンからもビルドされない** ——
型検査すらできない。deploy されるのは SvelteKit のページと、
`/xrpc/[...path]` を `mcp.etzhayyim.com` へ中継する 1 本の route だけである。

この形は fleet 全体の既知クラスで、superproject の検出器
`verify-appview-facade`（「migrated appviews whose deployed handler is not the
file a reader opens」）が現在 146 件を数えている。この repo はその 1 件である。
検出器自身の実測（2026-08-15、appview 329 本）では **`src/app.ts` facade を持つ
のが 147 本、wrangler の main が SvelteKit のビルド出力なのが 175 本**である。

**ただし「app.ts と SvelteKit のどちらが正本か」は問いの立て方が誤っている** ——
下記のとおり、この workspace の正本言語はそのどちらでもない。

## ⚠ 公開ページが、自分の設定と矛盾している

`svelte/src/routes/+page.svelte` は訪問者にこう表示する:

- **Routes 0** / “No public route is declared next to this app surface.”
- “No public vars are declared in the nearest wrangler config.”

同じディレクトリの `wrangler.jsonc` は **route を 2 本、var を 8 個**宣言して
いる。ページの値はビルド時に生成されたのではなく **`+page.svelte` に literal で
焼かれており**、`relativePath` は抽出前の monorepo パスのままである。
これも検出器 `verify-appview-page-summary`（337 件）が数えているクラス。

## いま在るもの — 16 ファイル / 29,888 バイト

| 面 | ファイル | バイト |
|---|---|---|
| **deploy されない handler** | `…/src/app.ts` | 5,460 |
| deploy される page | `…/svelte/src/routes/+page.svelte` | 3,149 |
| deploy される XRPC 中継 | `…/svelte/src/routes/xrpc/[...path]/+server.ts` | 2,804 |
| Worker 設定 | `…/wrangler.jsonc` | 1,321 |
| actor 記述子 | `…/kotodama.jsonld` | 2,003 |
| 設計 | `CLAUDE.md` | 8,909 |
| 移行チェックリスト | `MIGRATION-TODO.md` | 3,088 |
| svelte の設定 4 本 | `package.json` / `svelte.config.js` / `tsconfig.json` / `vite.config.ts` / `app.html` | 1,570 |
| 由来・権利・識別 | `NOTICE` / `PROJECT.jsonld` / `README.edn` / `migration.edn` | 1,584 |

**テストは 0 本。`src/` 直下は無い**（`src/` は appview の中にある）。

## 呼び先が 1 つも解決しない

このコードが実行時に触る先を実測した（2026-08-18）:

| ホスト | 役割 | DNS |
|---|---|---|
| `ongakuka.etzhayyim.com` | 公開ホスト（wrangler の route） | **NXDOMAIN** |
| `ong4k4k4.etzhayyim.com` | 同（nanoid 側） | **NXDOMAIN** |
| `mcp.etzhayyim.com` | `+server.ts` の XRPC 中継先 | **NXDOMAIN** |
| `dispatcher.etzhayyim.com` | `app.ts` の BPMN proxy 先 | **NXDOMAIN** |

deploy 先も、deploy されたコードが呼ぶ相手も、いま存在しない。

## 由来（custody）

`migration.edn` は出所を `etzhayyim/root` の tree `0426d779` と宣言し、
`:identity {:allowed-additions ["README.edn" "migration.edn"]}` という
custody 契約を自分で持っている。GitHub 上のその tree を引いて照合した:

- 元の **14 ファイル / 29,319 バイトが 1 バイトも変わらず保存されている**
  （sha256 を検証器に固定）
- 追加は宣言どおり `README.edn` と `migration.edn` の 2 件のみ
- 14 + 2 = 16 ファイル、29,319 + 569 = 29,888 バイト

## 正本言語は TypeScript ではない — この repo に正本言語のコードは 0 本

superproject `CLAUDE.md` の repo-wide 規則は、第一の runtime を
**kotoba wasm > clojurewasm > ClojureScript > nbb**（JVM と bb は降格）と定め、
新規の生 JS（`.mjs` / `.cjs`）と `.sh` を禁じている。**TypeScript はこの順序に
入っていない。**

この repo の production source を数えると:

| | 本数 |
|---|---|
| TypeScript（`.ts`） | **3** |
| 正本言語（`.cljs` / `.cljc` / `.clj` / `.kotoba`） | **0** |

（`scripts/` は除外して数えている —— この repo で唯一の `.cljs` は今回足した
検証器そのもので、それを数えると差が見えなくなる。）

つまり **deploy される側も、読み手が開く側も、どちらも非正本の言語**である。
`app.ts` を生かすか SvelteKit に寄せるかという二択は、どちらを選んでも
正本には着地しない。

### 正本の形は同じ org に実在する

`cloud-itonami/cloud-itonami-marketplace-listing`:

```
src/listingops/*.cljc     ← 読み手が開くソース
shadow-cljs.edn           ← :target :esm → dist/worker.js
wrangler.jsonc            ← "main": "dist/worker.js"
```

**deploy される bundle が、読むソースからコンパイルされている**ので、この repo が
抱えている facade の形は構造的に起こり得ない。実測（2026-08-18、cloud-itonami の
checkout 済み repo）では **shadow-cljs を持つ appview が 87 本**、SvelteKit の
ビルド出力を配っているものが 99 本ある。判断（governor / policy）を `.kotoba` に
置いた例も同 org に複数ある（`cloud-itonami-isco-1212/kotoba/governor_decision.kotoba` 等）。

なお Worker の入口を当面 cljs に置くのは ADR-2606290000 の判断で、kotoba の
ingress capability が `:native-aot` / `:wasm-aot` とも `pending` だからである
（superproject `CLAUDE.md`）。**入口は cljs、判断は `.kotoba`** が今日の形。

## 既知の欠陥（測定済み・直していない）

1. **deploy される handler と読み手が開くファイルが別**（上記）。
2. **公開ページが自分の設定と矛盾する**（routes 0 vs 2、vars 0 vs 8）。
3. **`app.ts` はビルド不能** —— import する SDK をどの package.json も宣言せず、
   lockfile も無い。
4. **`listTracks` が空配列を返す stub** で、`note` に
   `Phase 0 stub — wire createKyselyDb in next iteration` と書いてある。
   「まだ実装していない」と「1 曲も無い」が呼び出し側から区別できない。
   しかも `MIGRATION-TODO.md` のチェックリストは **Kysely を剥がせ**と要求して
   いるので、この note が示す次の一歩は移行方針と正面から衝突する。
5. **`MIGRATION-TODO.md` のチェックボックスが 7 件すべて未チェック**のまま
   「ad-pixel codemod は完了」とだけ宣言されている。憲章適合の手動レビューは
   未実施であると文書自身が書いている。

**どれも直していない。** 1〜3 は「この appview の正本をどちらにするか」
（app.ts を生かすのか、SvelteKit 側に寄せるのか）というオーナー決定が先で、
4 は永続層の決定を要し、5 は憲章解釈だからである。

## 検証

```bash
nbb scripts/verify-docs-claims.cljs .     # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
