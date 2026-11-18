/*
 * Copyright (c) 2026 Visionary Imaging Services, Inc.
 * SPDX-License-Identifier: MIT
 *
 * メイン画面の Plug-ins メニュー → 窓を開く。シリーズを選ぶ → フォルダと名前を選ぶ → 書き出す。
 * NIfTI にするのは本体（host.nifti.exportSeries・H78。向き〔LPS↔RAS〕は本体が 1 か所で決める）、
 * 2 ファイル形式（.hdr＋.img）に直すのはバックエンド面（JAR）。成果物は 1 ジョブ 1 ファイルなので、.img → .hdr の順に 2 回走らせ、
 * 2 つとも作れてから選んだフォルダへ保存する（片方だけ残さない）。
 */
const FORMATS = [
  { id: "nifti-pair", label: "NIfTI 2 ファイル形式（.hdr ＋ .img）", note: "float32・単一ファイルの NIfTI と同じ向き情報（sform/qform）を持ちます。" },
];

/** 本体の書き出し（host.nifti.exportSeries）が断る理由。未知のコードは握りつぶさずコードを添える。 */
const REFUSED = {
  "desktop-only": "デスクトップ版でのみ使えます。",
  "series-not-found": "シリーズが見つかりません。一覧を更新してからもう一度選んでください。",
  "anonymize-failed": "画素の取り出し（匿名化の処理）に失敗しました。",
  "burnin-incomplete": "画素に焼き込まれた文字の塗りつぶしが済んでいない画像があり、書き出せません。",
  "burnin-declared": "画像に「焼き込み文字あり」と記録されているため、書き出せません。",
  "burnin-risky-modality": "この種類（US・XA・DX など）は画素に患者情報が焼き込まれていることが多く、マスクを登録していない画像は書き出せません。",
  "burnin-risky-sop-class": "二次キャプチャ・可視光・PDF の画像は、マスクを登録していないと書き出せません。",
  "burnin-unknown-modality": "モダリティが分からない画像は、焼き込みの有無を判断できないため書き出せません。",
  "nifti-no-geometry": "位置の情報（IOP・IPP）が無い画像のため、NIfTI の向きを決められません。",
  "volume-sheared": "ガントリーチルト（スライスが斜めに並ぶ）のため書き出せません。チルト補正をした画像で書き出してください。",
  "nifti-mosaic": "モザイク形式（1 枚に多数のスライスがタイル状に入る）の画像は、正しい向き・位置の NIfTI にできないため書き出せません。",
  "nifti-uneven-phases": "位置ごとの画像の枚数が揃っていないため、時相（4D）として並べられません。",
  "nifti-ambiguous-phases": "時相の順序を決められません（時相の番号・取得時刻が無い、または重なっています）。",
  "npz-uneven-spacing": "スライスの間隔が一定でないか、欠けたスライスがあります。等間隔のシリーズでないと NIfTI にできません。",
  "npz-duplicate-positions": "同じ位置に 2 枚以上の画像があります。",
  "npz-mixed-geometry": "向き・大きさ・画素間隔が異なる画像が混ざっているため、1 つのボリュームにできません。",
  "npz-not-grayscale": "カラー（RGB）の画像は書き出せません。",
  "npz-pixels-unreadable": "画素を読み出せない画像があります（圧縮形式が展開できないなど）。",
  "npz-too-large": "大きすぎて書き出せません（元の画素で 1 GiB まで）。",
  "npz-empty": "画像がありません。",
};

/** このプラグインの変換（JAR）が断る理由。 */
const CONVERT_REFUSED = {
  "bad-input-path": "本体が置いた NIfTI のファイルを読めませんでした。",
  "not-nifti": "NIfTI（gzip）として読めません。",
  "not-nifti1": "NIfTI-1（リトルエンディアン）ではありません。",
  "has-extension": "拡張つきの NIfTI は変換できません。",
  "unsupported-type": "float32 以外の画素の型は変換できません。",
  "unsupported-dims": "3 次元・4 次元以外は変換できません。",
  "bad-dim": "画像の大きさが不正です。",
  "too-large": "大きすぎて変換できません（16 GiB まで）。",
  "truncated": "画素が足りません（ファイルが途中で切れています）。",
  "trailing-data": "画素のあとに余分なデータがあります。",
};

// 本体のプラグイン窓は暗い背景。明るい色にする（暗い背景に暗い文字にしない）。
const OK = "#7ee2a0";
const NG = "#ff8a80";

export function activate(host) {
  const w = host.openWindow({ title: "研究用画像形式の書き出し", width: 620, height: 660 });
  const root = document.createElement("div");
  Object.assign(root.style, { padding: "12px 14px", fontSize: "13px", lineHeight: "1.6", fontFamily: "system-ui, sans-serif" });
  w.container.appendChild(root);

  const el = (tag, attrs = {}, text) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === "style") Object.assign(e.style, v);
      else e.setAttribute(k, v);
    }
    if (text !== undefined) e.textContent = text;
    return e;
  };
  const row = (label, ...kids) => {
    const r = el("div", { style: { display: "flex", alignItems: "center", gap: "8px", margin: "6px 0" } });
    r.append(el("div", { style: { width: "130px", opacity: "0.85", flex: "none" } }, label), ...kids);
    return r;
  };
  const btn = (testid, text) =>
    el("button", { "data-testid": testid, style: { padding: "4px 10px", cursor: "pointer", whiteSpace: "nowrap", flex: "none" } }, text);

  root.append(
    el("div", { style: { fontWeight: "600", fontSize: "14px" } }, "研究用画像形式の書き出し"),
    el("div", { style: { opacity: "0.8", marginBottom: "6px" } }, "研究・教育用です。診断には使えません。患者情報はファイルに書きません。"),
  );

  const format = el("select", { "data-testid": "rf-format", style: { padding: "3px 6px" } });
  for (const f of FORMATS) format.append(el("option", { value: f.id }, f.label));
  const formatNote = el("div", { style: { opacity: "0.75", margin: "0 0 4px 138px" } }, FORMATS[0].note);
  format.addEventListener("change", () => {
    formatNote.textContent = FORMATS.find((f) => f.id === format.value)?.note ?? "";
  });

  let series = null;
  let dir = null;
  const pickSeries = btn("rf-pick-series", "シリーズを選ぶ…");
  const seriesLabel = el("span", { "data-testid": "rf-series-label", style: { opacity: "0.8" } }, "（未選択）");
  const pickDir = btn("rf-pick-dir", "フォルダを選ぶ…");
  const dirLabel = el("span", { "data-testid": "rf-dir-label", style: { opacity: "0.8", wordBreak: "break-all" } }, "（未選択）");
  const name = el("input", { "data-testid": "rf-name", value: "volume", style: { width: "200px", padding: "4px 6px", border: "1px solid #cdd5de", borderRadius: "5px" } });
  const nameHint = el("span", { style: { opacity: "0.7" } }, ".hdr / .img が付きます");
  const run = btn("rf-run", "書き出す");
  const cancel = btn("rf-cancel", "中止");
  cancel.style.display = "none";
  const status = el("div", { "data-testid": "rf-status", style: { margin: "10px 0 4px", minHeight: "1.6em" } });
  const result = el("div", { "data-testid": "rf-result", style: { opacity: "0.9", wordBreak: "break-all" } });

  // ⚠ el() の第 3 引数は文字列。子要素は append で足す
  const buttons = el("div", { style: { margin: "10px 0 0 138px", display: "flex", gap: "8px" } });
  buttons.append(run, cancel);

  root.append(
    row("形式", format),
    formatNote,
    row("シリーズ", pickSeries, seriesLabel),
    row("保存先のフォルダ", pickDir, dirLabel),
    row("ファイル名", name, nameHint),
    el("div", { style: { margin: "8px 0 0 138px", opacity: "0.75" } }, "同じ名前のファイルがあっても上書きはしません（.hdr・.img の両方に同名があるときは、2 つとも「名前 (2)」のように別名で保存します）。空の新しいフォルダを選ぶのが確実です。"),
    buttons,
    status,
    result,
  );

  const safeName = () => name.value.trim().replace(/\.(hdr|img)$/i, "");
  const nameOk = () => /^[A-Za-z0-9._-]{1,80}$/.test(safeName());
  const say = (text, color) => {
    status.textContent = text;
    status.style.color = color ?? "";
  };
  const ready = () => {
    run.disabled = !(series && dir && nameOk());
    name.style.borderColor = nameOk() ? "#cdd5de" : NG;
    name.style.color = "#111";
  };
  name.addEventListener("input", ready);
  ready();

  pickSeries.addEventListener("click", async () => {
    const r = await host.db.pickSeries({ title: "書き出すシリーズを選ぶ", multiple: false });
    if (r && r.length) {
      series = r[0];
      seriesLabel.textContent = `選択済み（${series.seriesUid.slice(-12)}）`;
    }
    ready();
  });
  pickDir.addEventListener("click", async () => {
    const r = await host.file.pickDirectory({ title: "保存先のフォルダを選ぶ" });
    if (r.ok) {
      dir = r;
      dirLabel.textContent = r.path;
    } else if (!r.canceled) {
      say(`フォルダを選べませんでした: ${r.error ?? ""}`, NG);
    }
    ready();
  });

  let busy = false;
  let abort = null;
  const setBusy = (b) => {
    busy = b;
    for (const c of [run, pickSeries, pickDir, name, format]) c.disabled = b || (c === run && !(series && dir && nameOk()));
    cancel.style.display = b ? "" : "none";
  };

  /** ジョブ 1 本。結果は例外でなく値で返る。 */
  const job = async (part, path, label) => {
    const r = await host.runBackendJob({ op: "convert", part, path }, {
      signal: abort.signal,
      onProgress: (p) => say(`${label}… ${Math.round((p ?? 0) * 100)}%`),
    });
    if (!r.ok) return { cancelled: !!r.cancelled, error: r.error ?? "job-failed" };
    if (r.result?.error) return { error: r.result.error, detail: r.result.detail };
    return { artifact: r.result.__artifact, dim: r.result.dim, frames: r.result.frames };
  };

  run.addEventListener("click", async () => {
    if (busy || !(series && dir && nameOk())) return;
    setBusy(true);
    result.textContent = "";
    abort = new AbortController();
    const base = safeName();
    let exported = null;
    try {
      say("シリーズを NIfTI にしています…");
      const ex = await host.nifti.exportSeries({ studyUid: series.studyUid, seriesUid: series.seriesUid });
      if (!ex.ok) {
        say(`書き出せませんでした: ${REFUSED[ex.error] ?? `理由コード: ${ex.error}`}`, NG);
        return;
      }
      exported = ex;
      // 画素（長さを全部検査する）→ ヘッダ の順。両方できてから保存する。
      const img = await job("img", ex.path, "画素（.img）を作っています");
      if (img.cancelled) return say("中止しました。何も保存していません。");
      if (img.error) return say(`変換できませんでした: ${CONVERT_REFUSED[img.error] ?? `理由コード: ${img.error}`}`, NG);
      const hdr = await job("hdr", ex.path, "ヘッダ（.hdr）を作っています");
      if (hdr.cancelled) return say("中止しました。何も保存していません。");
      if (hdr.error) return say(`変換できませんでした: ${CONVERT_REFUSED[hdr.error] ?? `理由コード: ${hdr.error}`}`, NG);

      say("保存しています…");
      const saved = [];
      for (const [art, ext] of [[hdr.artifact, "hdr"], [img.artifact, "img"]]) {
        const want = `${base}.${ext}`;
        const s = await host.file.saveJobArtifactTo(art.jobId, { dirToken: dir.dirToken, name: want });
        if (!s.ok) {
          say(`保存できませんでした（${want}）: ${s.error ?? ""}${saved.length ? `。${saved[0]} は保存済みです` : ""}`, NG);
          return;
        }
        saved.push(s.filePath);
      }
      const leaf = (p) => String(p).split(/[\\/]/).pop();
      const stemOf = (p, ext) => leaf(p).replace(new RegExp(`\\.${ext}$`, "i"), "");
      const stemHdr = stemOf(saved[0], "hdr");
      const stemImg = stemOf(saved[1], "img");
      if (stemHdr !== stemImg) {
        // 片方だけ同名のファイルがあった。2 つの名前が揃っていないので、対として読めない
        say(`対になる名前が食い違いました（${leaf(saved[0])} と ${leaf(saved[1])}）。片方だけ同じ名前のファイルがあったためです。名前を揃えるか、新しい空のフォルダを選んでやり直してください。`, NG);
        result.textContent = saved.join("\n");
        result.style.whiteSpace = "pre-line";
        return;
      }
      const dimText = (hdr.dim ?? []).join(" × ");
      const renamed = stemHdr !== base ? `同じ名前のファイルがあったため「${stemHdr}」で保存しました（既存のファイルは上書きしていません）。` : "";
      say(`書き出しました（${ex.slices} 枚${ex.frames > 1 ? `・${ex.frames} 時相` : ""}）。${renamed}`, OK);
      result.textContent = `${saved.join("\n")}\n大きさ: ${dimText}（float32）`;
      result.style.whiteSpace = "pre-line";
    } catch (e) {
      say(`失敗しました: ${e?.message ?? e}`, NG);
    } finally {
      if (exported) await host.nifti.release(exported.path).catch(() => {});
      abort = null;
      setBusy(false);
      ready();
    }
  });
  cancel.addEventListener("click", () => abort?.abort());
}
