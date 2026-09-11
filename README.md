# app-ongakuka

**音楽家（ongakuka）—— 歌詞と style prompt から曲を生成する AI 音楽サービスの
appview。** 名前が機能を示さないので先に名乗る（この workspace の規約）。
`ongakuka` は「音楽家」であって、この repo が持つのは**その公開面（appview）**
である —— 生成そのものは murakumo の audio 推論側にあり、ここには無い。

`etzhayyim/root` の `60-apps/etzhayyim-project-ongakuka` からの抽出物で、
**2026-08-18 に TypeScript/Svelte から ClojureScript へ移行した**（ADR-0002）。
数字はすべて `scripts/verify-docs-claims.cljk` が tree から再計算して検査する。

## deploy されるものは、いま読んでいるソースである

```
src/ongakuka/route.cljk    判断（どの handler が答えるか）  ← 純 .cljc、テスト対象
src/ongakuka/view.cljk     ページ（jp-go-dds の hiccup）    ← 純 .cljc、テスト対象
src/ongakuka/worker.cljk   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js             ← wrangler.jsonc の "main" が指すもの
```

移行前は `main` が SvelteKit のビルド出力を指し、読み手が開く `src/app.ts` は
**どの bundle にも入っていなかった**（ADR-0001 が測って記録した）。いまは
`main` が指す bundle が上のソースからコンパイルされたものなので、その形は
構造的に起こり得ない。`scripts/verify-docs-claims.cljk` が
**shadow の出力先と wrangler の `main` と export の ns 名の 3 つが噛み合って
いること**を検査し、噛み合わなくなれば落ちる。

判断を `.cljc` に置いてあるのは、ブラウザもビルドも無しにテストするためであり、
ingress capability が qualify した時に **最初に `.kotoba` へ移る部分**だからで
ある（入口を当面 cljs に置くのは ADR-2606290000 の判断）。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| GET | `/health` | 生存確認。deploy された面が答えることを外から確かめられる |
| POST | `/xrpc/:nsid` | XRPC を MCP router へ中継する |
| OPTIONS | `/xrpc/*` | CORS preflight |

**この表の出所は `ongakuka.route/routes` で、ページもそこから描く。** 移行前の
ページは `routeCount: 0` と `vars: []` を literal で持っており、隣の
`wrangler.jsonc` が route 2・var 8 を宣言していることに気づけなかった。いまは
route 表を渡す側が持ち、ページは描くだけなので、両者がずれる余地が無い。

## いま在るもの — 19 ファイル

| 面 | ファイル |
|---|---|
| 判断・描画・edge | `src/ongakuka/{route.cljc, view.cljc, worker.cljs}` |
| テスト | `test/ongakuka/route_test.cljk`（5 tests / 21 assertions） |
| ビルド | `deps.edn` / `shadow-cljs.edn` |
| Worker 設定 | `appview/…/wrangler.jsonc` |
| actor 記述子 | `appview/…/kotodama.jsonld` |
| 設計 | `CLAUDE.md` / `MIGRATION-TODO.md` |
| 由来・権利・識別 | `NOTICE` / `PROJECT.jsonld` / `README.edn` / `migration.edn` |
| 文書 | `README.md` / `docs/operator-quickstart.md` / `docs/adr/*.edn` |

**production の TypeScript は 0 本、正本言語（`.cljs`/`.cljc`）が 4 本。**
移行前は 3 対 0 だった。この 2 つの数は検証器の claim なので、TS が戻れば落ちる
——撤去したパスに戻る場合（`removed-by-migration-absent`）も、別名で入る場合
（`production-ts-files`）も、別々の claim が捕まえる。

## ページが出す値・出さない値

env の**キー名**は出すが、値は出さない —— **中継先を除いて**。
`AGENTGATEWAY_MCP_ROUTER_URL` の値だけは、どこへ中継するかを運用者が見る
必要があるので意図的に表示する。

smoke はこれを**2 つの独立した印**で見る: 別の var に置いた sentinel が
出ていないこと、そして中継先の値が出ていること。片方だけだと「全部隠す」
実装も「全部出す」実装も通ってしまう。

（2026-08-18 の訂正: 以前このページは「キー名のみ。値は出さない」と書きながら
中継先の値を出していた。sentinel が表示されない var に付いていたため、
実際に出ている唯一の値を検査できていなかった。）

## UI

基盤は `kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム）。
色・寸法は `--hig-*` トークン契約だけで書き、raw hex も px フォントサイズも
置かない。app 固有 CSS は 3 行。CSS は外部リクエストゼロの方針どおり
`shadow.resource/inline` で bundle に焼く。

決定論的 audit（`kotoba-lang/design-quality`）で **100.00 / 100（gate 95）**。

## 呼び先が 1 つも解決しない（移行では直らない）

| ホスト | 役割 | DNS |
|---|---|---|
| `ongakuka.etzhayyim.com` | 公開ホスト（wrangler の route） | **NXDOMAIN** |
| `ong4k4k4.etzhayyim.com` | 同（nanoid 側） | **NXDOMAIN** |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **NXDOMAIN** |

deploy 先も中継先も、いま存在しない。`/xrpc/` は到達できなければ **502 を返す**
——成功と同じ形で隠さない。

## 由来（custody）

`migration.edn` は出所を `etzhayyim/root` の tree `0426d779` と宣言し、
`:allowed-additions` に `README.edn` と `migration.edn` を持つ。移行後の状態:

- 継承した 7 ファイル（15,584 バイト）は**いまも 1 バイトも変わっていない**
  （sha256 を検証器に固定）
- `wrangler.jsonc` は**意図的に変更**した（`main` の付け替え、消えた
  SvelteKit client を指す `assets` の撤去、`APP_FRAMEWORK` の更新）
- TypeScript/Svelte の 8 ファイルは**移行で撤去**した。検証器はその 8 パスを
  名指しで「不在であること」を検査する —— byte 合計は「TS が消えた」と言えない

## 残っている欠陥（移行では直っていない）

1. **`listTracks` が未実装**。移行前は空配列と `Phase 0 stub` の note を返して
   おり、「まだ実装していない」と「1 曲も無い」が呼び出し側から区別できなかった。
   移行後の Worker はこの経路を**持たない**（下記「持ち越さなかったもの」）。
2. **`MIGRATION-TODO.md` のチェックボックス 7 件が未チェック**のまま。憲章適合の
   手動レビューは未実施であると文書自身が書いている。

### 持ち越さなかったもの（黙って消していない）

移行前の `src/app.ts` にあってどこにも deploy されていなかった経路のうち、
次は**意図的に移していない**:

- `compose` → `dispatcher.etzhayyim.com` への POST（宛先が NXDOMAIN）
- `/blobs/:key` の B2 配信（`B2_KEY_ID` 等の binding が `wrangler.jsonc` に無い）
- `listTracks`（上記 1）

**動かない経路を移植して「移行済み」と言わないため**である。必要になった時点で
`route.cljc` に足し、テストと binding を伴って戻す。

## デザインシステムの検査は 2 本ある

`dads-table` が在ることを 1 本で見る形は**落ちない検査**だった —— それは view が
出力する markup であって、CSS が 1 バイトも入っていないページにも現れる。
実測（このページ、2026-08-18）:

| 探す文字列 | CSS 込み | CSS 無し |
|---|---|---|
| `dads-table` | 74 | **6**（0 にならない） |
| `--color-primitive-blue` | 45 | **0** |

だから 2 本に割った。**component を使ったか**（`class="dads-table"`）と、
**stylesheet が実際に入ったか**（`--color-primitive-blue`）は別の主張である。
CSS を外してビルドし直すと後者だけが赤くなることを確認済み。

design-quality のスコアはこの区別をしない（デザインシステムを完全に外しても
96.63 で PASS する）。「在る」と言えるのはこの smoke の 2 本目だけ。

## 検証

```bash
kbb --backend sci scripts/verify-docs-claims.cljk .          # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
テストとビルドは `docs/operator-quickstart.md`。
