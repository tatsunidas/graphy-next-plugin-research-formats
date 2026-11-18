/*
 * Copyright (c) 2026 Visionary Imaging Services, Inc.
 * SPDX-License-Identifier: MIT
 *
 * メイン画面の Plug-ins メニュー → 窓を開く。形式を選び、シリーズを選んで書き出す。
 * NIfTI にするのは本体（host.nifti.exportSeries・H78。向き〔LPS↔RAS〕は本体が 1 か所で決める）、
 * 形式への組み立てはバックエンド面（JAR）。成果物は 1 ジョブ 1 ファイルなので:
 *   - NIfTI 2 ファイル形式: .img → .hdr の順に 2 回走らせ、両方できてから選んだフォルダへ保存する（片方だけ残さない）。
 *   - BIDS: データセット全体を 1 つの ZIP にして、保存ダイアログで保存する（本体の保存 API は入れ子のフォルダを作れない）。
 */
const FORMATS = [
  { id: "nifti-pair", label: "NIfTI 2 ファイル形式（.hdr ＋ .img）", note: "float32・単一ファイルの NIfTI と同じ向き情報（sform/qform）を持ちます。" },
  { id: "bids", label: "BIDS データセット（.zip）", note: "MR の anat（T1w・T2w など。3 次元）を、被験者の連番（sub-001…）で BIDS の構造にまとめた ZIP です。取得パラメータ（TR・TE など）の JSON は含みません。DWI・func・PET・CT は対象外です。" },
];

/** BIDS の anat の suffix（仕様の非パラメトリックの一覧）。JAR の `ANAT_SUFFIXES` と同じ。 */
const ANAT_SUFFIXES = ["T1w", "T2w", "FLAIR", "PDw", "PDT2", "T2starw", "UNIT1", "angio", "inplaneT1", "inplaneT2"];

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
  "bids-4d-not-anat": "時相つき（4 次元）の画像は anat にできません。3 次元のシリーズだけを選んでください。",
  "bids-no-series": "シリーズが 1 本もありません。",
  "bids-too-many": "シリーズが多すぎます（200 本まで）。",
  "bids-too-large": "データセットが大きすぎます（合計 1 GiB まで。本体の保存の仕組み上、数 GB の ZIP には向きません）。シリーズを分けて書き出してください。",
  "bids-bad-suffix": "この種類は選べません（MR の anat のみ）。",
  "bids-bad-subject": "被験者番号は 1〜999 の整数にしてください。",
  "bids-bad-session": "セッション番号は 1〜99 の整数にしてください（空なら無し）。",
  "bids-bad-name": "データセット名は英数字・空白・- _ の 80 字までにしてください。",
  "bids-bad-description": "説明が長すぎます（2000 字まで）。",
  "bids-bad-item": "シリーズの指定が不正です。",
};

// 本体のプラグイン窓は暗い背景。明るい色にする（暗い背景に暗い文字にしない）。
const OK = "#7ee2a0";
const NG = "#ff8a80";
const BORDER = "#cdd5de";

export function activate(host) {
  const w = host.openWindow({ title: "研究用画像形式の書き出し", width: 640, height: 800 });
  const root = document.createElement("div");
  Object.assign(root.style, { padding: "12px 14px", fontSize: "13px", lineHeight: "1.6", fontFamily: "system-ui, sans-serif" });
  w.container.appendChild(root);

  // ⚠ el() の第 3 引数は文字列。子要素は append で足す
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
    const r = el("div", { style: { display: "flex", alignItems: "center", gap: "8px", margin: "6px 0", flexWrap: "wrap" } });
    r.append(el("div", { style: { width: "130px", opacity: "0.85", flex: "none" } }, label), ...kids);
    return r;
  };
  const btn = (testid, text) =>
    el("button", { "data-testid": testid, style: { padding: "4px 10px", cursor: "pointer", whiteSpace: "nowrap", flex: "none" } }, text);
  const field = (testid, value, width) =>
    el("input", { "data-testid": testid, value, style: { width, padding: "4px 6px", border: `1px solid ${BORDER}`, borderRadius: "5px", color: "#111" } });
  const note = (text) => el("div", { style: { margin: "4px 0 4px 138px", opacity: "0.75" } }, text);

  root.append(
    el("div", { style: { fontWeight: "600", fontSize: "14px" } }, "研究用画像形式の書き出し"),
    el("div", { style: { opacity: "0.8", marginBottom: "6px" } }, "研究・教育用です。診断には使えません。患者情報はファイルに書きません。"),
  );

  const format = el("select", { "data-testid": "rf-format", style: { padding: "3px 6px" } });
  for (const f of FORMATS) format.append(el("option", { value: f.id }, f.label));
  const formatNote = el("div", { style: { opacity: "0.75", margin: "0 0 4px 138px" } }, FORMATS[0].note);
  root.append(row("形式", format), formatNote);

  // ---- NIfTI 2 ファイル形式のパネル ----
  const pairPanel = el("div", { "data-testid": "rf-panel-pair" });
  let series = null;
  let dir = null;
  const pickSeries = btn("rf-pick-series", "シリーズを選ぶ…");
  const seriesLabel = el("span", { "data-testid": "rf-series-label", style: { opacity: "0.8" } }, "（未選択）");
  const pickDir = btn("rf-pick-dir", "フォルダを選ぶ…");
  const dirLabel = el("span", { "data-testid": "rf-dir-label", style: { opacity: "0.8", wordBreak: "break-all" } }, "（未選択）");
  const name = field("rf-name", "volume", "200px");
  const nameHint = el("span", { style: { opacity: "0.7" } }, ".hdr / .img が付きます");
  pairPanel.append(
    row("シリーズ", pickSeries, seriesLabel),
    row("保存先のフォルダ", pickDir, dirLabel),
    row("ファイル名", name, nameHint),
    note("同じ名前のファイルがあっても上書きはしません（.hdr・.img の両方に同名があるときは、2 つとも「名前 (2)」のように別名で保存します）。空の新しいフォルダを選ぶのが確実です。"),
  );

  // ---- BIDS のパネル ----
  const bidsPanel = el("div", { "data-testid": "rf-panel-bids", style: { display: "none" } });
  const bname = field("rf-bids-name", "dataset", "220px");
  const bdesc = el("textarea", { "data-testid": "rf-bids-desc", rows: "2", placeholder: "データセットの説明（任意）", style: { width: "330px", padding: "4px 6px", border: `1px solid ${BORDER}`, borderRadius: "5px", color: "#111" } });
  const suffixSel = el("select", { "data-testid": "rf-bids-suffix", style: { padding: "3px 6px" } });
  for (const s of ANAT_SUFFIXES) suffixSel.append(el("option", { value: s }, s));
  const subj = field("rf-bids-subject", "1", "50px");
  const sess = field("rf-bids-session", "", "50px");
  sess.setAttribute("placeholder", "なし");
  const add = btn("rf-bids-add", "シリーズを追加…");
  const list = el("div", { "data-testid": "rf-bids-list", style: { margin: "6px 0 0 0" } });
  bidsPanel.append(
    row("データセット名", bname, el("span", { style: { opacity: "0.7" } }, "氏名・患者 ID を入れないでください")),
    row("説明", bdesc),
    el("div", { style: { margin: "10px 0 2px", fontWeight: "600" } }, "含めるシリーズ（1 本ずつ追加します）"),
    note("選択窓でシリーズを選んだ直後に、そのシリーズの種類（suffix）・被験者番号・セッション番号を決めます。同じ被験者・セッション・種類が複数あると run-01、run-02… が付きます。プラグインはシリーズの種類（モダリティ）を判定できないので、MR 以外は追加しないでください。"),
    row("次に追加するもの", el("span", { style: { opacity: "0.85" } }, "種類"), suffixSel, el("span", { style: { opacity: "0.85" } }, "被験者"), subj, el("span", { style: { opacity: "0.85" } }, "セッション"), sess, add),
    list,
  );
  /** @type {{studyUid:string, seriesUid:string, suffix:string, subject:number, session:number|null}[]} */
  const items = [];

  const buttons = el("div", { style: { margin: "10px 0 0 138px", display: "flex", gap: "8px" } });
  const run = btn("rf-run", "書き出す");
  const cancel = btn("rf-cancel", "中止");
  cancel.style.display = "none";
  buttons.append(run, cancel);
  const status = el("div", { "data-testid": "rf-status", style: { margin: "10px 0 4px", minHeight: "1.6em" } });
  const result = el("div", { "data-testid": "rf-result", style: { opacity: "0.9", wordBreak: "break-all", whiteSpace: "pre-line" } });
  root.append(pairPanel, bidsPanel, buttons, status, result);

  const isBids = () => format.value === "bids";
  const safeName = () => name.value.trim().replace(/\.(hdr|img)$/i, "");
  const nameOk = () => /^[A-Za-z0-9._-]{1,80}$/.test(safeName());
  const bnameOk = () => /^[A-Za-z0-9][A-Za-z0-9 _-]{0,79}$/.test(bname.value.trim());
  const wholeIn = (text, lo, hi) => /^\d{1,4}$/.test(text.trim()) && +text >= lo && +text <= hi;
  const say = (text, color) => {
    status.textContent = text;
    status.style.color = color ?? "";
  };
  let busy = false;
  const ready = () => {
    const ok = isBids() ? items.length > 0 && bnameOk() : !!(series && dir && nameOk());
    run.disabled = busy || !ok;
    name.style.borderColor = nameOk() ? BORDER : NG;
    bname.style.borderColor = bnameOk() ? BORDER : NG;
    subj.style.borderColor = wholeIn(subj.value, 1, 999) ? BORDER : NG;
    sess.style.borderColor = sess.value.trim() === "" || wholeIn(sess.value, 1, 99) ? BORDER : NG;
    add.disabled = busy || !wholeIn(subj.value, 1, 999) || !(sess.value.trim() === "" || wholeIn(sess.value, 1, 99));
  };
  for (const c of [name, bname, subj, sess]) c.addEventListener("input", ready);

  const renderList = () => {
    list.textContent = "";
    items.forEach((it, i) => {
      const r = el("div", { "data-testid": `rf-bids-row-${i}`, style: { display: "flex", gap: "10px", alignItems: "center", margin: "3px 0", flexWrap: "wrap" } });
      const rm = btn(`rf-bids-remove-${i}`, "削除");
      rm.addEventListener("click", () => {
        if (busy) return;
        items.splice(i, 1);
        renderList();
        ready();
      });
      r.append(
        el("span", { style: { opacity: "0.7", width: "1.5em" } }, `${i + 1}.`),
        el("span", {}, `シリーズ …${it.seriesUid.slice(-12)}`),
        el("span", { style: { fontWeight: "600" } }, it.suffix),
        el("span", {}, `被験者 ${it.subject}`),
        el("span", {}, it.session === null ? "セッションなし" : `セッション ${it.session}`),
        rm,
      );
      list.append(r);
    });
    if (items.length === 0) list.append(el("div", { style: { opacity: "0.6" } }, "（まだありません）"));
  };
  renderList();

  const showPanel = () => {
    pairPanel.style.display = isBids() ? "none" : "";
    bidsPanel.style.display = isBids() ? "" : "none";
    formatNote.textContent = FORMATS.find((f) => f.id === format.value)?.note ?? "";
    result.textContent = "";
    say("");
    ready();
  };
  format.addEventListener("change", showPanel);

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
  add.addEventListener("click", async () => {
    if (busy) return;
    const r = await host.db.pickSeries({ title: `追加するシリーズ（${suffixSel.value}・被験者 ${subj.value.trim()}）を選ぶ`, multiple: false });
    if (!r || !r.length) return;
    const dup = items.some((it) => it.seriesUid === r[0].seriesUid);
    if (dup) {
      say("そのシリーズはもう追加しています。", NG);
      return;
    }
    items.push({
      studyUid: r[0].studyUid,
      seriesUid: r[0].seriesUid,
      suffix: suffixSel.value,
      subject: +subj.value.trim(),
      session: sess.value.trim() === "" ? null : +sess.value.trim(),
    });
    say("");
    renderList();
    ready();
  });

  let abort = null;
  const setBusy = (b) => {
    busy = b;
    for (const c of [pickSeries, pickDir, name, format, bname, bdesc, suffixSel, subj, sess]) c.disabled = b;
    cancel.style.display = b ? "" : "none";
    ready();
  };

  /** ジョブ 1 本。結果は例外でなく値で返る。 */
  const job = async (payload, label) => {
    const r = await host.runBackendJob(payload, {
      signal: abort.signal,
      onProgress: (p) => say(`${label}… ${Math.round((p ?? 0) * 100)}%`),
    });
    if (!r.ok) return { cancelled: !!r.cancelled, error: r.error ?? "job-failed" };
    if (r.result?.error) return { error: r.result.error, detail: r.result.detail };
    if (r.result?.cancelled) return { cancelled: true };
    return { result: r.result };
  };
  const refusedText = (code, table) => table[code] ?? `理由コード: ${code}`;
  const leaf = (p) => String(p).split(/[\\/]/).pop();

  /** NIfTI 2 ファイル形式: .img → .hdr → 保存。 */
  const runPair = async (released) => {
    const base = safeName();
    say("シリーズを NIfTI にしています…");
    const ex = await host.nifti.exportSeries({ studyUid: series.studyUid, seriesUid: series.seriesUid });
    if (!ex.ok) return say(`書き出せませんでした: ${refusedText(ex.error, REFUSED)}`, NG);
    released.push(ex.path);
    // 画素（長さを全部検査する）→ ヘッダ の順。両方できてから保存する。
    const img = await job({ op: "convert", part: "img", path: ex.path }, "画素（.img）を作っています");
    if (img.cancelled) return say("中止しました。何も保存していません。");
    if (img.error) return say(`変換できませんでした: ${refusedText(img.error, CONVERT_REFUSED)}`, NG);
    const hdr = await job({ op: "convert", part: "hdr", path: ex.path }, "ヘッダ（.hdr）を作っています");
    if (hdr.cancelled) return say("中止しました。何も保存していません。");
    if (hdr.error) return say(`変換できませんでした: ${refusedText(hdr.error, CONVERT_REFUSED)}`, NG);

    say("保存しています…");
    const saved = [];
    for (const [art, ext] of [[hdr.result.__artifact, "hdr"], [img.result.__artifact, "img"]]) {
      const want = `${base}.${ext}`;
      const s = await host.file.saveJobArtifactTo(art.jobId, { dirToken: dir.dirToken, name: want });
      if (!s.ok) return say(`保存できませんでした（${want}）: ${s.error ?? ""}${saved.length ? `。${saved[0]} は保存済みです` : ""}`, NG);
      saved.push(s.filePath);
    }
    const stemOf = (p, ext) => leaf(p).replace(new RegExp(`\\.${ext}$`, "i"), "");
    const stemHdr = stemOf(saved[0], "hdr");
    const stemImg = stemOf(saved[1], "img");
    if (stemHdr !== stemImg) {
      // 片方だけ同名のファイルがあった。2 つの名前が揃っていないので、対として読めない
      say(`対になる名前が食い違いました（${leaf(saved[0])} と ${leaf(saved[1])}）。片方だけ同じ名前のファイルがあったためです。名前を揃えるか、新しい空のフォルダを選んでやり直してください。`, NG);
      result.textContent = saved.join("\n");
      return;
    }
    const renamed = stemHdr !== base ? `同じ名前のファイルがあったため「${stemHdr}」で保存しました（既存のファイルは上書きしていません）。` : "";
    say(`書き出しました（${ex.slices} 枚${ex.frames > 1 ? `・${ex.frames} 時相` : ""}）。${renamed}`, OK);
    result.textContent = `${saved.join("\n")}\n大きさ: ${(hdr.result.dim ?? []).join(" × ")}（float32）`;
  };

  /** BIDS: 各シリーズを NIfTI にして、データセットの ZIP を作り、保存ダイアログで保存する。 */
  const runBids = async (released) => {
    const paths = [];
    for (let i = 0; i < items.length; i++) {
      if (abort.signal.aborted) return say("中止しました。何も保存していません。");
      say(`シリーズ ${i + 1}/${items.length} を NIfTI にしています…`);
      const ex = await host.nifti.exportSeries({ studyUid: items[i].studyUid, seriesUid: items[i].seriesUid });
      if (!ex.ok) return say(`シリーズ ${i + 1} を書き出せませんでした: ${refusedText(ex.error, REFUSED)}`, NG);
      released.push(ex.path);
      paths.push(ex.path);
    }
    const payload = {
      op: "bids",
      name: bname.value.trim(),
      description: bdesc.value,
      items: items.map((it, i) => ({ path: paths[i], subject: it.subject, session: it.session, suffix: it.suffix })),
    };
    const z = await job(payload, "データセットを作っています");
    if (z.cancelled) return say("中止しました。何も保存していません。");
    if (z.error) return say(`データセットを作れませんでした: ${refusedText(z.error, CONVERT_REFUSED)}`, NG);
    const art = z.result.__artifact;
    const s = await host.file.saveJobArtifact(art.jobId, {
      defaultName: z.result.__artifactName ?? "bids-dataset.zip",
      filters: [{ name: "BIDS データセット（ZIP）", extensions: ["zip"] }],
    });
    if (!s.ok) return say(s.canceled ? "保存をやめました。何も保存していません。" : `保存できませんでした: ${s.error ?? ""}`, s.canceled ? undefined : NG);
    say(`書き出しました（${items.length} シリーズ・被験者 ${z.result.subjects} 人）。展開して使ってください。`, OK);
    result.textContent = `${s.filePath}\n${(z.result.files ?? []).join("\n")}`;
  };

  run.addEventListener("click", async () => {
    if (busy || run.disabled) return;
    setBusy(true);
    result.textContent = "";
    abort = new AbortController();
    const released = [];
    try {
      if (isBids()) await runBids(released);
      else await runPair(released);
    } catch (e) {
      say(`失敗しました: ${e?.message ?? e}`, NG);
    } finally {
      for (const p of released) await host.nifti.release(p).catch(() => {});
      abort = null;
      setBusy(false);
    }
  });
  cancel.addEventListener("click", () => abort?.abort());
  ready();
}
