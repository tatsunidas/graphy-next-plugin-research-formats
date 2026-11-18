/*
 * Copyright (c) 2026 Visionary Imaging Services, Inc.
 * SPDX-License-Identifier: MIT
 */
package com.vis.graphy.researchformats;

import com.vis.graphynext.plugin.spi.GraphyPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * バックエンド面。{@code op}:
 * <ul>
 *   <li>{@code convert} … 本体の {@code host.nifti.exportSeries}（H78）が置いた {@code .nii.gz} を {@code part}（{@code hdr} / {@code img}）に変換する。
 *       成果物は 1 ジョブ 1 ファイルなので、画面は {@code img}（長さを全部検査する）→ {@code hdr} の順に 2 回呼ぶ。</li>
 * </ul>
 * 値は JDK の型（Map・List・数値・文字列）だけで返す。断るときは {@code {error: <理由コード>}}（例外は投げない）。
 */
public class ResearchFormatsPlugin implements GraphyPlugin {

    /** plugin.json の id と同じ。本体は、この名前のフォルダの下のファイルだけ渡してくる。 */
    static final String PLUGIN_ID = "vis-research-formats";
    private static final String OUT_DIR = "graphy-plugin-research-formats";
    private static final long STALE_MS = 2L * 60 * 60 * 1000;

    private final Path tmpRoot;

    public ResearchFormatsPlugin() {
        this(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** テスト用: 一時フォルダの置き場を差し替える。 */
    ResearchFormatsPlugin(Path tmpRoot) {
        this.tmpRoot = tmpRoot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object run(Map<String, Object> args) throws Exception {
        BiConsumer<Double, String> progress = args.get("__progress") instanceof BiConsumer<?, ?> p
                ? (BiConsumer<Double, String>) p : (a, b) -> { };
        BooleanSupplier cancelled = args.get("__cancelled") instanceof BooleanSupplier c ? c : () -> false;
        String op = String.valueOf(args.getOrDefault("op", "convert"));
        if ("bids".equals(op)) {
            return bids(args, progress, cancelled);
        }
        if (!"convert".equals(op)) {
            return Map.of("error", "unknown-op");
        }
        String part = String.valueOf(args.get("part"));
        if (!"hdr".equals(part) && !"img".equals(part)) {
            return Map.of("error", "bad-part");
        }
        Path in = allowedInput(args.get("path"));
        if (in == null) {
            return Map.of("error", "bad-input-path");
        }
        sweepStale();
        Path dir = tmpRoot.resolve(OUT_DIR).resolve(UUID.randomUUID().toString());
        Files.createDirectories(dir);
        Path out = dir.resolve("volume." + part);
        try {
            NiftiPairWriter.Header h = NiftiPairWriter.readHeader(in);
            if ("hdr".equals(part)) {
                NiftiPairWriter.writeHdr(in, out);
            } else {
                boolean ok = NiftiPairWriter.writeImg(in, out, p -> progress.accept(p, "画素を書き出しています…"), cancelled);
                if (!ok) {
                    deleteTree(dir);
                    return Map.of("cancelled", true);
                }
            }
            List<Integer> dim = new ArrayList<>();
            for (int i = 0; i < h.dims(); i++) {
                dim.add(h.dim()[i]);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("__artifact", out.toString()); // 本体が預かって移す（一時フォルダの下の普通のファイルに限る）
            r.put("__artifactName", "volume." + part);
            r.put("part", part);
            r.put("bytes", Files.size(out));
            r.put("dim", dim);
            r.put("frames", h.frames());
            return r;
        } catch (NiftiPairWriter.Refused e) {
            deleteTree(dir);
            return Map.of("error", e.code(), "detail", e.getMessage());
        } catch (IOException | RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
    }

    /**
     * {@code op=bids}: {@code items}（各 {@code {path, subject, session?, suffix}}）・{@code name}・{@code description} から BIDS の ZIP を作る。
     * 入力は {@link #allowedInput} と同じ規則（本体が置いた一時フォルダの下の実在の {@code .nii.gz} だけ）。
     */
    private Object bids(Map<String, Object> args, BiConsumer<Double, String> progress, BooleanSupplier cancelled) throws Exception {
        if (!(args.get("items") instanceof List<?> raw) || raw.isEmpty()) {
            return Map.of("error", "bids-no-series");
        }
        if (raw.size() > BidsWriter.MAX_ENTRIES) {
            return Map.of("error", "bids-too-many");
        }
        List<BidsWriter.Entry> entries = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                return Map.of("error", "bids-bad-item");
            }
            Path in = allowedInput(m.get("path"));
            if (in == null) {
                return Map.of("error", "bad-input-path");
            }
            Integer subject = wholeNumber(m.get("subject"));
            if (subject == null) {
                return Map.of("error", "bids-bad-subject");
            }
            Integer session = null;
            if (m.get("session") != null) {
                session = wholeNumber(m.get("session"));
                if (session == null) {
                    return Map.of("error", "bids-bad-session");
                }
            }
            entries.add(new BidsWriter.Entry(in, subject, session, String.valueOf(m.get("suffix"))));
        }
        String name = args.get("name") instanceof String s ? s : null;
        String description = args.get("description") instanceof String s ? s : "";
        sweepStale();
        Path dir = tmpRoot.resolve(OUT_DIR).resolve(UUID.randomUUID().toString());
        Files.createDirectories(dir);
        String stem = name == null ? "dataset" : BidsWriter.safeFileStem(name);
        Path out = dir.resolve("bids-" + stem + ".zip");
        try {
            BidsWriter.Result r = BidsWriter.write(out, entries, name, description,
                    p -> progress.accept(p, "データセットを作っています…"), cancelled);
            if (r == null) {
                deleteTree(dir);
                return Map.of("cancelled", true);
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("__artifact", out.toString());
            res.put("__artifactName", out.getFileName().toString());
            res.put("bytes", Files.size(out));
            res.put("files", r.files());
            res.put("subjects", r.subjects());
            return res;
        } catch (NiftiPairWriter.Refused e) {
            deleteTree(dir);
            return Map.of("error", e.code(), "detail", e.getMessage());
        } catch (IOException | RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
    }

    /** JSON の数（整数・実数）を、小数部の無い整数のときだけ int にする。 */
    private static Integer wholeNumber(Object o) {
        if (o instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) < 1e9) {
            return n.intValue();
        }
        return null;
    }

    /**
     * 受け付ける入力は、本体が {@code host.nifti.exportSeries} で置く
     * {@code <tmp>/graphy-plugin-nifti/vis-research-formats/} の下の、実在の普通の {@code .nii.gz} だけ
     * （シンボリックリンクは不可。任意のファイルを読ませない）。
     */
    private Path allowedInput(Object pathArg) throws IOException {
        if (!(pathArg instanceof String s) || s.isBlank()) {
            return null;
        }
        Path p = Path.of(s).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || !p.getFileName().toString().toLowerCase().endsWith(".nii.gz")) {
            return null;
        }
        Path allowedDir = tmpRoot.resolve("graphy-plugin-nifti").resolve(PLUGIN_ID);
        if (!Files.isDirectory(allowedDir)) {
            return null;
        }
        Path allowed = allowedDir.toRealPath();
        return p.toRealPath().startsWith(allowed) ? p.toRealPath() : null;
    }

    /** 2 時間より古い自分の一時フォルダを消す（本体が成果物を移した後の空のフォルダ・異常終了の残り）。 */
    private void sweepStale() {
        Path base = tmpRoot.resolve(OUT_DIR);
        if (!Files.isDirectory(base)) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Stream<Path> s = Files.list(base)) {
            for (Path d : (Iterable<Path>) s::iterator) {
                if (now - Files.getLastModifiedTime(d).toMillis() > STALE_MS) {
                    deleteTree(d);
                }
            }
        } catch (IOException ignored) {
            // 掃除の失敗で変換を止めない
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 次回の掃除に任せる
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
