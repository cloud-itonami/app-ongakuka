# etzhayyim-project-ongakuka — 音楽家 (AI Music Generation)

共通ルールは `60-apps/CLAUDE.md` を参照。

## Overview

ongakuka.etzhayyim.com — Suno クラスの AI 音楽生成。歌詞 + style prompt から歌唱 + 伴奏のミックス、または stem 分割を生成する。Generation backend は `murakumo:inference/audio` (Mac fleet)。実装は **既存 OSS (DiffRhythm / YuE) を `serve_plain.py` 流に MLX でラップ** → 日本語歌詞 LoRA → 自前 2 段 LM (semantic → acoustic) の順で進める。

## Identifier (ADR-0019 atproto-native)

| 層 | 値 |
|---|---|
| Primary DID | `did:plc:ongakuka` (Phase 5 `plc.etzhayyim.com` で genesis) |
| Handle | `ongakuka.etzhayyim.com` |
| Legacy nanoid | `0ng4k4k4` (grandfather, deprecate 2026-10-01) |
| NSID | `com.etzhayyim.ongakuka.*` |

## Project Actor Composition (1 project = N actor DIDs)

1 track = 1 project = 1 convoId。各 actor の成果物は `projectId` field でスコープ。

| Path DID | 役割 | 主モデル |
|---|---|---|
| `did:web:ongakuka.etzhayyim.com` | controller | — |
| `did:web:ongakuka.etzhayyim.com:actor:lyricist` | 歌詞補完 / 翻訳 / 韻律調整 | murakumo text (etzhayyim-moe-moe-kyun-general / gemma-4) |
| `did:web:ongakuka.etzhayyim.com:actor:composer` | semantic token (旋律 + 構成) 生成 | audio LM stage-1 |
| `did:web:ongakuka.etzhayyim.com:actor:vocalist` | 歌声 acoustic token + phoneme alignment | audio LM stage-2 (vocal branch) |
| `did:web:ongakuka.etzhayyim.com:actor:arranger` | 伴奏 acoustic token (drums/bass/keys/...) | audio LM stage-2 (instr branch) |
| `did:web:ongakuka.etzhayyim.com:actor:mixer` | vocoder + LUFS 正規化 + マスタリング | DAC/EnCodec decoder |
| `did:web:ongakuka.etzhayyim.com:actor:critic` | CLAP score + 歌詞 alignment QA + 著作権 sim 検査 | CLAP + text classifier |

actor 間連携は **convo chat (`sendProjectMessage`)** + AT Record commit。stage 出力 (stem) は `com.etzhayyim.ongakuka.stem` record + `actorDid` field で帰属。

## Domain Model

| 概念 | NSID | Graph node |
|---|---|---|
| 楽曲 | `com.etzhayyim.ongakuka.track` | `OkTrack` |
| Stem | `com.etzhayyim.ongakuka.stem` | `OkStem` |
| Style 参照 (prompt or embedding) | `com.etzhayyim.ongakuka.style` | `OkStyle` |
| 生成イベント (audit + metering) | `com.etzhayyim.ongakuka.generation` | `OkGeneration` |

### Edge predicates

| Predicate | Domain → Range |
|---|---|
| `HAS_STEM` | OkTrack → OkStem |
| `USED_STYLE` | OkTrack → OkStyle |
| `PRODUCED_BY` | OkStem → (actor DID) |
| `GENERATED_BY` | OkTrack → OkGeneration |
| `REGENERATED_FROM` | OkGeneration → OkGeneration (lineage) |

## XRPC Surface

| NSID | Type | 用途 |
|---|---|---|
| `com.etzhayyim.ongakuka.compose` | procedure | enqueue 1 track (returns trackUri 即時) |
| `com.etzhayyim.ongakuka.regenerate` | procedure | section / stem 部分再生成 |
| `com.etzhayyim.ongakuka.listTracks` | query | offset/limit list |
| `com.etzhayyim.ongakuka.getTrack` | query | track + stems + last generation |
| `com.etzhayyim.ongakuka.health` | procedure | health probe (bootstrap) |

## Triggers (kotodama.jsonld 予定)

```jsonc
{
  "triggers": {
    "subscribeRepos": {
      "collections": [
        "app.bsky.feed.post",
        "app.bsky.feed.like",
        "app.bsky.feed.repost",
        "app.bsky.graph.follow",
        "com.etzhayyim.ongakuka.track",
        "com.etzhayyim.ongakuka.stem",
        "com.etzhayyim.ongakuka.style",
        "com.etzhayyim.ongakuka.generation"
      ]
    }
  }
}
```

## Reactive Pipeline (Design E 3-Tier Write)

```
XRPC compose
  → handleAietzhayyimAppsOngakukaCompose
    → ComAtprotoRepoCreateRecord("track", {status:"queued", projectId, ...})
       ↓ onCommit (subscribeRepos: com.etzhayyim.ongakuka.track)
       handleAietzhayyimAppsOngakukaTrack:
         track.status === "queued" →
           lyricist.complete() → status "lyric"
         track.status === "lyric" →
           composer.compose() → semantic tokens (Preferences blob ref)
         track.status === "compose" →
           parallel(vocalist.sing(), arranger.arrange()) → stem records (T2)
         track.status === "vocal" || "arrange" →
           mixer.mix() → final blob, track.blobKey set
         track.status === "mix" →
           critic.review() → status "published" or "rejected"
         track.status === "published" →
           [DERIVED] AppBskyFeedPost (T1 social, public release)
```

| Tier | 内容 | API |
|---|---|---|
| **T1 Social** | 「新曲: {title} 🎵 {trackUri}」 | `app.bsky.feed.post` (derived from track→published commit) |
| **T2 Domain** | track / stem / style / generation | `com.atproto.repo.createRecord` → RisingWave `vertex_okTrack`/... |
| **T3 State** | 歌詞下書き / user generation quota / 課金 / private style refs | `Preferences()` |
| **Blob** | wav / flac / mp3 / stem / embedding | `uploadBlob` (SHA-256 content-addressed B2)、`blobKey` field 経由参照 |

## Inference Backend

`murakumo:inference/audio@1.0.0` (新規) を呼ぶ。murakumo CLAUDE.md §Audio / Music 参照。

| Stage | Provider call | Model (Phase 0) |
|---|---|---|
| lyricist | `murakumo:inference/text` chat-completions | `gemma-4-12b-it` / `qwen3.5-9b` |
| composer + vocalist + arranger | `murakumo:inference/audio` text-to-music or text-to-music-stems | `diffrhythm-1.2-ja` |
| mixer (vocoder) | `murakumo:inference/audio` vocoder | `dac-44k` |
| critic | text classifier + CLAP sim | local TS in worker |

**Phase 進行**:
1. **Phase 0** (MVP): DiffRhythm を `serve_plain.py` でラップ、`audio_pool` (32GB+ Mac) で起動。1 track = single-shot text-to-music
2. **Phase 1**: 日本語歌詞 LoRA fine-tune (etzhayyim-moe-moe-kyun MLX LoRA pipeline 流用)。stem 分離出力対応
3. **Phase 2**: 自前 2 段 LM (semantic → acoustic, EnCodec backend) を Opus distill で構築

## CRITICAL: Copyright / Consent Invariants

1. **学習データ**: permissive (CC0/CC-BY)、ユーザー持ち込み (consent 明示)、自社制作のみ。著作権不明データを学習 corpus に入れない
2. **Style ref**: `com.etzhayyim.ongakuka.style` の `license` field が `permissive|own|licensed` の場合のみ AT Record 公開可。`unknown` は Preferences (T3) 限定
3. **Critic gate**: 生成物が学習データ中の特定楽曲と CLAP cosine > 0.92 なら `status="rejected"` で publish 抑止 (memorization 検出)
4. **Lyrics PII**: 歌詞に実在個人名/連絡先が含まれる場合 critic が PII flag → T3 only
5. **No covers without licensing**: style prompt に既存アーティスト名を明示指定された場合は reject (parody/inspired はテキスト記述のみ許容)
6. **Output watermark**: final wav に inaudible watermark (audio-watermark-tooling、Phase 1 で導入) を埋め込む

## Cross-Project Dependencies

| Project | 関係 |
|---|---|
| `murakumo` | `inference/audio` provider (CRITICAL) |
| `kakin` | quota check (`CheckQuota` per compose) |
| `credits` | consumer spend / operator reward |
| `auth` | per-call ES256 Service Auth (`lxm=com.etzhayyim.ongakuka.compose`) |
| `signal` | private style ref / draft lyrics の field encrypt (`signal:v1:`) |
| `vault` | licensed sample/dataset の zero-knowledge 保管 |
| `well-becoming` | critic の品質 / 配慮スコア反映 |

## App Component (TS Native)

| Key | Value |
|---|---|
| Nanoid | `0ng4k4k4` |
| Folder | `wasm/etzhayyim-wasm-ongakuka-0ng4k4k4/` |
| Runtime | TS Native (`src/app.ts`, `"runtimeType": "worker"`) |
| Wrangler route | `ongakuka.etzhayyim.com/*` |
| Bindings | `HYPERDRIVE`, `B2_KEY_ID` / `B2_APPLICATION_KEY` (Backblaze B2 SigV4, ADR-0048; bucket `etzhayyim-ongakuka`), `MURAKUMO_SERVICE`, `AUTH_SERVICE`, `PDS_SERVICE`, `KAKIN_SERVICE`, `CREDITS_SERVICE` |

## Frontend (planned)

- Hono router + Svelte CSR (flat west Svelte packages)
- 画面: lyrics editor / style picker (prompt or upload) / generation queue / waveform player / stem mixer / critic feedback
- Deep-link: `https://ongakuka.etzhayyim.com/at/{handle}/com.etzhayyim.ongakuka.track/{rkey}`

## Migration Backlog

| 項目 | 状態 |
|---|---|
| Lexicon JSON × 9 (`00-contracts/lexicons/com/etzhayyim/apps/ongakuka/`) | DONE (2026-04-15) |
| Murakumo `inference/audio` spec 追記 | DONE (2026-04-15) |
| `kotodama.jsonld` + `src/app.ts` + `wrangler.jsonc` (T1 worker) | TODO |
| `30-graph/graph-schema/migrations/00XX_vertex_ongakuka_*.ts` | TODO |
| `audio_pool` Ansible group + Mac 32GB+ ノード調達 | TODO |
| DiffRhythm `serve_plain.py` 拡張 (`/api/audio/v1/music/*`) | TODO |
| `70-tools/etzhayyim/ongakuka.go` CLI subcommand (`etzhayyim ongakuka compose/list/get`) | TODO |
| `[[projects]]` / `[[mitama_actors]]` / `[[legacy_nanoids]]` 登録 (`deps.toml`) | TODO |
| Phase 1 日本語 LoRA dataset 収集 (consented + permissive) | TODO |
