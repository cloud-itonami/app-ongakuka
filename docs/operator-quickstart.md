# operator-quickstart

**この repo で今日実際にできることを、踏める形で上から書く。** 所要 5 分。
Cloudflare のアカウントも AWS も要らない。**deploy はできない**（§4 に理由と、
それを自分で確かめる手順）。

出力はすべて push 済みブランチの fresh clone を walk した実測である。

## 0. 前提

| 要るもの | 確認 | この walk で使った版 |
|---|---|---|
| git | `git --version` | 2.51.0 |
| nbb | `npx --yes nbb --version` | v1.4.208 |
| dig | `dig -v` | 任意（§3 だけ） |

## 1. 取得して、書いてあることが本当か検査する

```bash
git clone git@github.com:cloud-itonami/app-ongakuka.git
cd app-ongakuka
REPO=$PWD
npx --yes nbb scripts/verify-docs-claims.cljs .
```

末尾が `OK` なら README の数値・存在・不在は tree と一致している。
**exit 2（UNDETERMINED）は 0 ではない** —— tree を読み切れなかったという
別の答えで、「検査して問題なし」と混ぜない。

## 2. deploy される handler と、あなたが開くファイルの差を見る

これがこの repo で一番大事な 2 分である。

```bash
cd "$REPO/appview/etzhayyim-wasm-ongakuka-0ng4k4k4"

# (a) Worker の入口はどれか
grep '"main"' wrangler.jsonc
# (b) それは tree に在るか
test -e svelte/.svelte-kit/cloudflare/_worker.js && echo "在る" || echo "無い（ビルド出力）"
# (c) svelte 側は src/app.ts を参照しているか
grep -rn 'app\.ts\|kotodama-host-sdk' svelte/ | wc -l
# (d) app.ts が import する SDK を宣言している package.json はあるか
grep -rl 'kotodama-host-sdk' --include=package.json . | wc -l
```

実際の出力:

```
  "main": "svelte/.svelte-kit/cloudflare/_worker.js",
無い（ビルド出力）
       0
       0
```

読み方: **deploy されるのは SvelteKit のビルド出力**で、`src/app.ts`
（`compose` / `/blobs/:key` / actor DID 登録 / BPMN proxy が書いてある
5,460 バイト）は**そこから参照されておらず、依存として宣言もされていない**。
app.ts を読んでこの appview の挙動を推測すると外れる。

続けて、公開ページが自分の設定と矛盾していることを見る:

```bash
grep -E '"routeCount"|"routes": \[\]|"vars": \[\]' svelte/src/routes/+page.svelte
python3 -c "
import json,re
s=re.sub(r'^\s*//.*$','',open('wrangler.jsonc',encoding='utf8').read(),flags=re.M)
j=json.loads(s); print('wrangler declares vars:',len(j['vars']),'routes:',len(j['routes']))"
```

```
  "routeCount": 0,
  "routes": [],
  "vars": [],
wrangler declares vars: 8 routes: 2
```

ページは訪問者に「Routes 0」「No public route is declared next to this app
surface」「No public vars are declared in the nearest wrangler config」と
表示するが、**同じディレクトリの設定は route 2・var 8 を宣言している**。
ページの値は生成物ではなく `+page.svelte` に literal で焼かれている。

## 3. 呼び先が生きているか見る

```bash
for h in ongakuka.etzhayyim.com ong4k4k4.etzhayyim.com \
         mcp.etzhayyim.com dispatcher.etzhayyim.com; do
  printf '%-28s %s\n' "$h" "$(dig +short $h | head -1 || true)"
done
```

実際の出力（値が空 = A レコード無し）:

```
ongakuka.etzhayyim.com
ong4k4k4.etzhayyim.com
mcp.etzhayyim.com
dispatcher.etzhayyim.com
```

4 つとも解決しない。**deploy 先も、deploy されたコードが呼ぶ相手も無い。**
`+server.ts` は XRPC を `mcp.etzhayyim.com` へ中継し、`app.ts` は
`dispatcher.etzhayyim.com` へ POST する。

## 4. ビルドと deploy —— この周では走らせていない

`svelte/package.json` に `build` script は在る（`vite build`）。
**この walk では実行していない。** 理由は 2 つあり、どちらも正直に書く:

1. **lockfile が 0 件**で、`npm install` は解決結果が walk のたびに変わる。
2. この workspace は高負荷ビルドを同時 1 本に制限しており
   （superproject `CLAUDE.md` の resource governor）、この周は別セッションが
   lock を保持していた。実際に拒否された:

```bash
cd "$REPO/appview/etzhayyim-wasm-ongakuka-0ng4k4k4/svelte"
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- npm install
# resource-guard: build is already running (pid=…, repo=…/cloud-murakumo)
# exit 2
```

**迂回して直接 `npm install` を叩かないこと。** 走らせたい時は上のコマンドを
lock が空いてから使う。なお §2 の結論（app.ts がビルド対象に入っていない）は
ビルドの成否とは無関係に、宣言だけで決まっている。

deploy は `wrangler deploy` だが、**route が指すゾーンのホストが解決しない**
ので、成功しても誰も到達できない。superproject の deploy guard は
`origin/main` を含む checkout からの deploy しか許さない点も併せて注意。

## 5. ここに無いもの

- **テスト 0 本**、CI 無し、lockfile 無し
- 生成そのもの（murakumo の audio 推論側にある）
- `listTracks` の実装 —— 空配列と `Phase 0 stub` の note を返すだけで、
  「まだ実装していない」と「1 曲も無い」が呼び出し側から区別できない
- `MIGRATION-TODO.md` の 7 項目の憲章適合レビュー（全部未チェック）
