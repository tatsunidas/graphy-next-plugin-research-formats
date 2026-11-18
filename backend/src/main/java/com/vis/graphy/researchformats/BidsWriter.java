/*
 * Copyright (c) 2026 Visionary Imaging Services, Inc.
 * SPDX-License-Identifier: MIT
 */
package com.vis.graphy.researchformats;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 本体の NIfTI（単一ファイル・float32）を、BIDS（Brain Imaging Data Structure）のデータセットにして ZIP に詰める。
 *
 * <p>対象は MR の {@code anat}（3 次元）だけ。BIDS の仕様で確かめた範囲（2026-11-18）:
 * <ul>
 *   <li>画像は NIfTI（{@code .nii.gz} 推奨）。変換せず、本体が書いた NIfTI をそのまま入れる。</li>
 *   <li>{@code dataset_description.json} の必須は {@code Name} と {@code BIDSVersion}。</li>
 *   <li>名前は {@code sub-<label>[_ses-<label>][_run-<index>]_<suffix>.nii.gz}（エンティティの順: sub, ses, run）。</li>
 * </ul>
 * 取得パラメータの JSON（TR・TE など）は作らない（本体からタグが渡らない。値を作らない）。DWI・func・PET・CT は対象外。
 * 被験者は連番（{@code sub-001}…）。患者 ID・氏名・日付はどこにも出ない。
 */
final class BidsWriter {

    /** {@code dataset_description.json} の BIDSVersion。BIDS の仕様の例にある版（検証器で受理を確認）。 */
    static final String BIDS_VERSION = "1.10.1";

    /** 選べる suffix（MR の anat の非パラメトリック。仕様の一覧から）。 */
    static final List<String> ANAT_SUFFIXES = List.of(
            "T1w", "T2w", "FLAIR", "PDw", "PDT2", "T2starw", "UNIT1", "angio", "inplaneT1", "inplaneT2");

    static final int MAX_ENTRIES = 200;
    /**
     * 入力の合計の上限（バイト）。本体の保存ダイアログ経路（H53 `saveJobArtifact`）は成果物をいったん画面のメモリへ取り込むので、
     * 数 GB の ZIP には向かない（本体の仕様の注記）。それより十分手前で断る。
     */
    static final long MAX_TOTAL_BYTES = 1L << 30;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0,79}");
    private static final int MAX_DESCRIPTION = 2000;

    private BidsWriter() {
    }

    /** 1 本のシリーズ。{@code nii} は本体が書いた {@code .nii.gz}（許可フォルダの検査は呼び出し側）。 */
    record Entry(Path nii, int subject, Integer session, String suffix) {
    }

    /** 書き出した結果。{@code files} は ZIP の中のファイルの名前（画面に出す）。 */
    record Result(List<String> files, int subjects) {
    }

    static String safeFileStem(String name) {
        return name.trim().replace(' ', '-');
    }

    /** 検査（ファイルは作らない）。問題があれば {@link NiftiPairWriter.Refused}。 */
    static void validate(List<Entry> entries, String name, String description) throws NiftiPairWriter.Refused {
        if (entries == null || entries.isEmpty()) {
            throw new NiftiPairWriter.Refused("bids-no-series", "シリーズが 1 本もない");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new NiftiPairWriter.Refused("bids-too-many", entries.size() + " 本");
        }
        if (name == null || !NAME.matcher(name.trim()).matches()) {
            throw new NiftiPairWriter.Refused("bids-bad-name", "データセット名は英数字・空白・- _ の 80 字まで");
        }
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new NiftiPairWriter.Refused("bids-bad-description", "説明が長すぎる");
        }
        long total = 0;
        for (Entry e : entries) {
            try {
                total += Files.size(e.nii());
            } catch (IOException io) {
                throw new NiftiPairWriter.Refused("not-nifti", io.getMessage());
            }
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new NiftiPairWriter.Refused("bids-too-large", total + " バイト");
        }
        for (Entry e : entries) {
            if (!ANAT_SUFFIXES.contains(e.suffix())) {
                throw new NiftiPairWriter.Refused("bids-bad-suffix", String.valueOf(e.suffix()));
            }
            if (e.subject() < 1 || e.subject() > 999) {
                throw new NiftiPairWriter.Refused("bids-bad-subject", "被験者番号 " + e.subject());
            }
            if (e.session() != null && (e.session() < 1 || e.session() > 99)) {
                throw new NiftiPairWriter.Refused("bids-bad-session", "セッション番号 " + e.session());
            }
            NiftiPairWriter.Header h;
            try {
                h = NiftiPairWriter.readHeader(e.nii());
            } catch (IOException io) {
                throw new NiftiPairWriter.Refused("not-nifti", io.getMessage());
            }
            if (h.dims() != 3) {
                // anat の suffix は 3 次元の画像。4 次元（時相つき）は anat にできない
                throw new NiftiPairWriter.Refused("bids-4d-not-anat", "dim[0]=" + h.dims());
            }
        }
    }

    /** ZIP 内の相対パス（{@code sub-001/ses-01/anat/sub-001_ses-01_run-01_T1w.nii.gz}）を、入力の順に決める。 */
    static List<String> names(List<Entry> entries) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Entry e : entries) {
            count.merge(groupKey(e), 1, Integer::sum);
        }
        Map<String, Integer> next = new LinkedHashMap<>();
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            String sub = String.format("sub-%03d", e.subject());
            String ses = e.session() == null ? null : String.format("ses-%02d", e.session());
            StringBuilder file = new StringBuilder(sub);
            if (ses != null) {
                file.append('_').append(ses);
            }
            if (count.get(groupKey(e)) > 1) {
                file.append("_run-").append(String.format("%02d", next.merge(groupKey(e), 1, Integer::sum)));
            }
            file.append('_').append(e.suffix()).append(".nii.gz");
            out.add(sub + "/" + (ses == null ? "" : ses + "/") + "anat/" + file);
        }
        return out;
    }

    private static String groupKey(Entry e) {
        return e.subject() + "/" + e.session() + "/" + e.suffix();
    }

    static String datasetDescription(String name) {
        return "{\n  \"Name\": " + json(name.trim()) + ",\n  \"BIDSVersion\": " + json(BIDS_VERSION)
                + ",\n  \"DatasetType\": \"raw\"\n}\n";
    }

    static String participants(List<Entry> entries) {
        Set<Integer> subs = new TreeSet<>();
        for (Entry e : entries) {
            subs.add(e.subject());
        }
        StringBuilder sb = new StringBuilder("participant_id\n");
        for (int s : subs) {
            sb.append(String.format("sub-%03d", s)).append('\n');
        }
        return sb.toString();
    }

    static String readme(String name, String description) {
        StringBuilder sb = new StringBuilder("# ").append(name.trim()).append("\n\n");
        if (description != null && !description.isBlank()) {
            sb.append(description.strip()).append("\n\n");
        }
        sb.append("This dataset was exported from GRAPHY-Next (research use only; not for diagnosis).\n")
                .append("Images are NIfTI-1 (float32) written by the application; subjects are numbered sequentially and no patient\n")
                .append("identifiers or dates are stored. Only MR anatomical images (anat) are included; acquisition parameters\n")
                .append("(TR, TE, ...) are not recorded in sidecar JSON files.\n");
        return sb.toString();
    }

    /**
     * ZIP を {@code outZip} に書く。取り消されたら書きかけを消して false。ほかは例外。
     *
     * @param progress 0〜1（null 可）
     */
    static Result write(Path outZip, List<Entry> entries, String name, String description,
                        DoubleConsumer progress, BooleanSupplier cancelled) throws IOException, NiftiPairWriter.Refused {
        validate(entries, name, description);
        List<String> paths = names(entries);
        boolean done = false;
        try (OutputStream os = Files.newOutputStream(outZip); ZipOutputStream zip = new ZipOutputStream(os)) {
            // .nii.gz は圧縮済み。再圧縮しない（STORED と同じ並びで、CRC を先に計算せず流せる NO_COMPRESSION を使う）
            zip.setLevel(Deflater.NO_COMPRESSION);
            text(zip, "dataset_description.json", datasetDescription(name));
            text(zip, "participants.tsv", participants(entries));
            text(zip, "README", readme(name, description));
            long total = 0;
            for (Entry e : entries) {
                total += Files.size(e.nii());
            }
            long copied = 0;
            byte[] buf = new byte[1 << 20];
            for (int i = 0; i < entries.size(); i++) {
                zip.putNextEntry(new ZipEntry(paths.get(i)));
                try (InputStream in = Files.newInputStream(entries.get(i).nii())) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            return null;
                        }
                        zip.write(buf, 0, n);
                        copied += n;
                    }
                }
                zip.closeEntry();
                if (progress != null) {
                    progress.accept(total == 0 ? 1.0 : (double) copied / total);
                }
            }
            done = true;
        } finally {
            if (!done) {
                Files.deleteIfExists(outZip); // 断った・取り消した・失敗したとき、書きかけを残さない
            }
        }
        List<String> files = new ArrayList<>(List.of("dataset_description.json", "participants.tsv", "README"));
        files.addAll(paths);
        return new Result(files, (int) entries.stream().map(Entry::subject).distinct().count());
    }

    private static void text(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** JSON の文字列にする（制御文字・引用符・バックスラッシュをエスケープ）。 */
    static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
