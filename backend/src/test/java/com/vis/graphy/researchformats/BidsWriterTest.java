/*
 * Copyright (c) 2026 Visionary Imaging Services, Inc.
 * SPDX-License-Identifier: MIT
 */
package com.vis.graphy.researchformats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BIDS の ZIP（{@link BidsWriter}・{@code op=bids}）。名前・中身・断る形を、仕様で確かめた規則から手で書いた期待値と比べる。 */
class BidsWriterTest {

    @TempDir
    Path tmp;
    Path inDir;
    ResearchFormatsPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        inDir = tmp.resolve("graphy-plugin-nifti").resolve(ResearchFormatsPlugin.PLUGIN_ID);
        Files.createDirectories(inDir);
        plugin = new ResearchFormatsPlugin(tmp);
    }

    private Path vol(String name, int... dim) throws IOException {
        int n = 1;
        for (int d : dim) {
            n *= d;
        }
        return NiftiFixtures.gz(inDir, name, NiftiFixtures.nifti(dim, NiftiFixtures.ramp(n), 16, 32, 352f, false));
    }

    private static Map<String, Object> item(Path p, Object subject, Object session, String suffix) {
        Map<String, Object> m = new HashMap<>();
        m.put("path", p.toString());
        m.put("subject", subject);
        if (session != null) {
            m.put("session", session);
        }
        m.put("suffix", suffix);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(List<Map<String, Object>> items, String name, String description) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("op", "bids");
        args.put("items", items);
        args.put("name", name);
        args.put("description", description);
        return (Map<String, Object>) plugin.run(args);
    }

    private static Map<String, byte[]> unzip(Path zip) throws IOException {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (var e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                out.put(en.getName(), z.getInputStream(en).readAllBytes());
            }
        }
        return out;
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private int outputDirs() throws IOException {
        Path base = tmp.resolve("graphy-plugin-research-formats");
        if (!Files.isDirectory(base)) {
            return 0;
        }
        try (var s = Files.list(base)) {
            return (int) s.count();
        }
    }

    @Test
    void single_series_default_layout_and_contents() throws Exception {
        Path v = vol("a.nii.gz", 5, 4, 3);
        Map<String, Object> r = run(List.of(item(v, 1, null, "T1w")), "My Study", "説明文 \"quoted\"");
        Path zip = Path.of((String) r.get("__artifact"));
        assertEquals("bids-My-Study.zip", zip.getFileName().toString());
        Map<String, byte[]> z = unzip(zip);
        assertEquals(List.of("dataset_description.json", "participants.tsv", "README", "sub-001/anat/sub-001_T1w.nii.gz"),
                new ArrayList<>(z.keySet()));
        // 必須の 2 項目（Name・BIDSVersion）。仕様の例にある版
        assertEquals("{\n  \"Name\": \"My Study\",\n  \"BIDSVersion\": \"1.10.1\",\n  \"DatasetType\": \"raw\"\n}\n",
                str(z.get("dataset_description.json")));
        assertEquals("participant_id\nsub-001\n", str(z.get("participants.tsv")));
        assertTrue(str(z.get("README")).contains("説明文 \"quoted\""));
        // NIfTI は本体が書いたまま（バイト一致）
        assertArrayEquals(Files.readAllBytes(v), z.get("sub-001/anat/sub-001_T1w.nii.gz"));
        assertEquals(1, r.get("subjects"));
        assertEquals(List.of("dataset_description.json", "participants.tsv", "README", "sub-001/anat/sub-001_T1w.nii.gz"), r.get("files"));
    }

    @Test
    void subjects_sessions_and_runs_are_named_in_spec_order() throws Exception {
        Path a = vol("a.nii.gz", 4, 4, 4);
        Path b = vol("b.nii.gz", 5, 4, 4);
        Path c = vol("c.nii.gz", 6, 4, 4);
        Path d = vol("d.nii.gz", 7, 4, 4);
        Path e = vol("e.nii.gz", 8, 4, 4);
        List<BidsWriter.Entry> entries = List.of(
                new BidsWriter.Entry(a, 1, null, "T1w"),    // 重複なし → run 無し
                new BidsWriter.Entry(b, 2, 1, "T2w"),       // セッションあり
                new BidsWriter.Entry(c, 2, 1, "FLAIR"),
                new BidsWriter.Entry(d, 3, null, "T1w"),    // 同じ（被験者・セッション・suffix）が 2 本 → run-01・run-02
                new BidsWriter.Entry(e, 3, null, "T1w"));
        assertEquals(List.of(
                "sub-001/anat/sub-001_T1w.nii.gz",
                "sub-002/ses-01/anat/sub-002_ses-01_T2w.nii.gz",
                "sub-002/ses-01/anat/sub-002_ses-01_FLAIR.nii.gz",
                "sub-003/anat/sub-003_run-01_T1w.nii.gz",
                "sub-003/anat/sub-003_run-02_T1w.nii.gz"), BidsWriter.names(entries));
        // 別の被験者・別の suffix の同名は run を付けない（同じ組だけ）
        assertEquals("participant_id\nsub-001\nsub-002\nsub-003\n", BidsWriter.participants(entries));
        // run とセッションが両方ある順序: sub, ses, run
        assertEquals(List.of("sub-001/ses-02/anat/sub-001_ses-02_run-01_T1w.nii.gz", "sub-001/ses-02/anat/sub-001_ses-02_run-02_T1w.nii.gz"),
                BidsWriter.names(List.of(new BidsWriter.Entry(a, 1, 2, "T1w"), new BidsWriter.Entry(b, 1, 2, "T1w"))));
    }

    @Test
    void refusals_make_no_zip_and_each_positive_counterpart_passes() throws Exception {
        Path ok = vol("ok.nii.gz", 4, 4, 4);
        assertNotNull(run(List.of(item(ok, 1, null, "T1w")), "ds", "").get("__artifact"), "正例（同じ形）は通る");
        int dirsBefore = outputDirs();

        Path fourD = vol("fourd.nii.gz", 4, 4, 4, 3);
        Path outside = NiftiFixtures.gz(tmp, "outside.nii.gz", NiftiFixtures.nifti(new int[]{4, 4, 4}, NiftiFixtures.ramp(64), 16, 32, 352f, false));
        Path notNifti = inDir.resolve("junk.nii.gz");
        Files.write(notNifti, "not gzip".getBytes());

        Map<String, Map<String, Object>> cases = new java.util.LinkedHashMap<>();
        cases.put("bids-4d-not-anat", run(List.of(item(fourD, 1, null, "T1w")), "ds", ""));
        cases.put("bad-input-path", run(List.of(item(outside, 1, null, "T1w")), "ds", ""));
        cases.put("not-nifti", run(List.of(item(notNifti, 1, null, "T1w")), "ds", ""));
        cases.put("bids-bad-suffix", run(List.of(item(ok, 1, null, "bold")), "ds", ""));   // anat 以外
        cases.put("bids-bad-suffix2", run(List.of(item(ok, 1, null, "CT")), "ds", ""));    // CT は仕様にない
        cases.put("bids-bad-subject", run(List.of(item(ok, 0, null, "T1w")), "ds", ""));
        cases.put("bids-bad-subject2", run(List.of(item(ok, -3, null, "T1w")), "ds", ""));
        cases.put("bids-bad-subject3", run(List.of(item(ok, 1.5, null, "T1w")), "ds", ""));
        cases.put("bids-bad-subject4", run(List.of(item(ok, "7", null, "T1w")), "ds", ""));
        cases.put("bids-bad-subject5", run(List.of(item(ok, 1000, null, "T1w")), "ds", ""));
        cases.put("bids-bad-session", run(List.of(item(ok, 1, 0, "T1w")), "ds", ""));
        cases.put("bids-bad-session2", run(List.of(item(ok, 1, 100, "T1w")), "ds", ""));
        cases.put("bids-bad-name", run(List.of(item(ok, 1, null, "T1w")), "../evil", ""));
        cases.put("bids-bad-name2", run(List.of(item(ok, 1, null, "T1w")), "", ""));
        cases.put("bids-bad-name3", run(List.of(item(ok, 1, null, "T1w")), "山田太郎", ""));
        cases.put("bids-bad-description", run(List.of(item(ok, 1, null, "T1w")), "ds", "x".repeat(2001)));
        cases.put("bids-no-series", run(List.of(), "ds", ""));
        // 合計の上限（1 GiB）。実ファイルは作らず、ヘッダ検査の前に合計だけで断る形を、疎ファイルで作る
        Path big = inDir.resolve("big.nii.gz");
        try (var ch = java.nio.channels.FileChannel.open(big, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            ch.position(BidsWriter.MAX_TOTAL_BYTES);
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
        }
        cases.put("bids-too-large", run(List.of(item(big, 1, null, "T1w")), "ds", ""));
        for (var e : cases.entrySet()) {
            assertEquals(e.getKey().replaceAll("\\d$", ""), e.getValue().get("error"), e.getKey());
            assertFalse(e.getValue().containsKey("__artifact"), e.getKey());
        }
        assertEquals(dirsBefore, outputDirs(), "断ったときは出力フォルダを残さない");
    }

    @Test
    void one_bad_item_among_good_ones_refuses_the_whole_dataset() throws Exception {
        Path ok = vol("ok2.nii.gz", 4, 4, 4);
        Path fourD = vol("fourd2.nii.gz", 4, 4, 4, 2);
        int before = outputDirs();
        Map<String, Object> r = run(List.of(item(ok, 1, null, "T1w"), item(fourD, 1, null, "T2w")), "ds", "");
        assertEquals("bids-4d-not-anat", r.get("error"));
        assertEquals(before, outputDirs());
    }

    @Test
    void description_and_name_cannot_break_the_json_or_path() throws Exception {
        Path v = vol("j.nii.gz", 4, 4, 4);
        Map<String, Object> r = run(List.of(item(v, 1, null, "T1w")), "a b-c_d", "line1\nline2 \\ \"q\" \t");
        Path zip = Path.of((String) r.get("__artifact"));
        assertEquals("bids-a-b-c_d.zip", zip.getFileName().toString());
        Map<String, byte[]> z = unzip(zip);
        assertEquals("{\n  \"Name\": \"a b-c_d\",\n  \"BIDSVersion\": \"1.10.1\",\n  \"DatasetType\": \"raw\"\n}\n", str(z.get("dataset_description.json")));
        assertEquals("\"a\\\\\\\"b\\n\\u0001\"", BidsWriter.json("a\\\"b\n\u0001"));
    }

    @Test
    void cancel_leaves_nothing_and_progress_reaches_one() throws Exception {
        Path v = vol("c1.nii.gz", 8, 8, 8);
        int before = outputDirs();
        Map<String, Object> args = new HashMap<>();
        args.put("op", "bids");
        args.put("items", List.of(item(v, 1, null, "T1w")));
        args.put("name", "ds");
        args.put("__cancelled", (java.util.function.BooleanSupplier) () -> true);
        Object c = plugin.run(args);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) c).get("cancelled"));
        assertEquals(before, outputDirs());

        List<Double> seen = new ArrayList<>();
        args.put("__cancelled", (java.util.function.BooleanSupplier) () -> false);
        args.put("__progress", (java.util.function.BiConsumer<Double, String>) (p, m) -> seen.add(p));
        Object ok = plugin.run(args);
        assertNotNull(((Map<?, ?>) ok).get("__artifact"));
        assertFalse(seen.isEmpty());
        assertEquals(1.0, seen.get(seen.size() - 1), 1e-9);
    }

    @Test
    void bad_arguments_are_refused_without_exceptions() throws Exception {
        assertEquals("bids-no-series", ((Map<?, ?>) plugin.run(Map.of("op", "bids"))).get("error"));
        assertEquals("bids-bad-item", ((Map<?, ?>) plugin.run(Map.of("op", "bids", "items", List.of("x")))).get("error"));
        assertEquals("bids-too-many", ((Map<?, ?>) plugin.run(Map.of("op", "bids", "items", java.util.Collections.nCopies(201, Map.of())))).get("error"));
    }
}
