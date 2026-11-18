/*
 * Copyright (c) 2026 Visionary Imaging Services, Inc.
 * SPDX-License-Identifier: MIT
 */
package com.vis.graphy.researchformats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NIfTI 単一ファイル（本体の書き出し）→ 2 ファイル形式。期待値は、本体の書き手のレイアウト（NIfTI-1 の仕様のバイト位置）から
 * テストで手に組んだ入力と、仕様のオフセットでの読み直しで作る（変換器の式を写さない）。
 */
class ResearchFormatsTest {

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

    private Path gz(String name, byte[] raw) throws IOException {
        return NiftiFixtures.gz(inDir, name, raw);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(Path in, String part) throws Exception {
        Object r = plugin.run(Map.of("op", "convert", "part", part, "path", in.toString()));
        return (Map<String, Object>) r;
    }

    private static Path artifact(Map<String, Object> r) {
        assertNotNull(r.get("__artifact"), String.valueOf(r));
        return Path.of((String) r.get("__artifact"));
    }

    @Test
    void three_d_pair_has_ni1_header_zero_offset_and_bit_identical_pixels() throws Exception {
        float[] data = NiftiFixtures.ramp(5 * 4 * 3);
        byte[] raw = NiftiFixtures.nifti(new int[]{5, 4, 3}, data, 16, 32, 352f, false);
        Path in = gz("a.nii.gz", raw);

        Path hdr = artifact(run(in, "hdr"));
        Path img = artifact(run(in, "img"));
        byte[] h = Files.readAllBytes(hdr);
        assertEquals(348, h.length);
        // magic は 4 バイト目から "ni1\0"、vox_offset は 0
        assertArrayEquals(new byte[]{'n', 'i', '1', 0}, new byte[]{h[344], h[345], h[346], h[347]});
        ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0f, hb.getFloat(108));
        // ほかの欄は 1 バイトも変わらない（magic と vox_offset 以外が元のヘッダと同じ）
        byte[] expected = new byte[348];
        System.arraycopy(raw, 0, expected, 0, 348);
        expected[345] = 'i';
        expected[346] = '1';
        ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putFloat(108, 0f);
        assertArrayEquals(expected, h);
        assertEquals(3, hb.getShort(40));
        assertEquals(16, hb.getShort(70));
        assertEquals(1, hb.getShort(254));
        assertEquals(12.25f, hb.getFloat(284));

        byte[] px = Files.readAllBytes(img);
        assertEquals(data.length * 4, px.length);
        byte[] rawPx = new byte[px.length];
        System.arraycopy(raw, 352, rawPx, 0, px.length);
        assertArrayEquals(rawPx, px, "画素（NaN を含む）が元とビット一致");
    }

    @Test
    void four_d_pair_keeps_dim_and_reports_frames() throws Exception {
        float[] data = NiftiFixtures.ramp(3 * 2 * 2 * 4);
        Path in = gz("d.nii.gz", NiftiFixtures.nifti(new int[]{3, 2, 2, 4}, data, 16, 32, 352f, false));
        Map<String, Object> r = run(in, "img");
        assertEquals(4, r.get("frames"));
        assertEquals(List.of(3, 2, 2, 4), r.get("dim"));
        assertEquals(data.length * 4L, Files.size(artifact(r)));
        assertEquals(348, Files.size(artifact(run(in, "hdr"))));
    }

    @Test
    void refusals_write_nothing_and_each_positive_counterpart_passes() throws Exception {
        float[] d = NiftiFixtures.ramp(2 * 2 * 2);
        Path ok = gz("ok.nii.gz", NiftiFixtures.nifti(new int[]{2, 2, 2}, d, 16, 32, 352f, false));
        assertNotNull(run(ok, "img").get("__artifact"), "正例（同じ形）は通る");

        // NIfTI-2 の sizeof_hdr
        byte[] n2 = NiftiFixtures.nifti(new int[]{2, 2, 2}, d, 16, 32, 352f, false);
        ByteBuffer.wrap(n2).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 540);
        // 既に 2 ファイル形式の magic
        byte[] ni1 = NiftiFixtures.nifti(new int[]{2, 2, 2}, d, 16, 32, 352f, false);
        ni1[345] = 'i';
        // 拡張つき・vox_offset が 352 でない
        byte[] withExt = NiftiFixtures.nifti(new int[]{2, 2, 2}, d, 16, 32, 352f, true);
        byte[] offset = NiftiFixtures.nifti(new int[]{2, 2, 2}, d, 16, 32, 400f, false);
        // int16・5 次元・0 のサイズ
        byte[] i16 = NiftiFixtures.nifti(new int[]{2, 2, 2}, d, 4, 16, 352f, false);
        byte[] five = NiftiFixtures.nifti(new int[]{2, 2, 2, 1, 1}, d, 16, 32, 352f, false);
        byte[] zero = NiftiFixtures.nifti(new int[]{2, 0, 2}, d, 16, 32, 352f, false);
        // ビッグエンディアン（sizeof_hdr のバイト列が逆）
        byte[] be = NiftiFixtures.nifti(new int[]{2, 2, 2}, d, 16, 32, 352f, false);
        ByteBuffer.wrap(be).order(ByteOrder.BIG_ENDIAN).putInt(0, 348);

        Map<String, byte[]> bad = new java.util.LinkedHashMap<>();
        bad.put("not-nifti1", n2);
        bad.put("not-nifti1b", ni1);
        bad.put("has-extension", withExt);
        bad.put("has-extensionb", offset);
        bad.put("unsupported-type", i16);
        bad.put("unsupported-dims", five);
        bad.put("bad-dim", zero);
        bad.put("not-nifti1c", be);
        for (var e : bad.entrySet()) {
            Path in = gz(e.getKey() + ".nii.gz", e.getValue());
            for (String part : List.of("hdr", "img")) {
                Map<String, Object> r = run(in, part);
                assertEquals(e.getKey().replaceAll("[bc]$", ""), r.get("error"), e.getKey() + " " + part);
                assertFalse(r.containsKey("__artifact"), e.getKey());
            }
        }
        assertTrue(noOutputs(), "断ったときは出力フォルダを残さない");
    }

    @Test
    void truncated_trailing_and_broken_gzip_are_refused_and_leave_no_partial_file() throws Exception {
        float[] d = NiftiFixtures.ramp(4 * 4 * 4);
        byte[] good = NiftiFixtures.nifti(new int[]{4, 4, 4}, d, 16, 32, 352f, false);
        assertNotNull(run(gz("good.nii.gz", good), "img").get("__artifact"));

        byte[] cut = java.util.Arrays.copyOf(good, good.length - 8);
        assertEquals("truncated", run(gz("cut.nii.gz", cut), "img").get("error"));

        byte[] more = java.util.Arrays.copyOf(good, good.length + 4);
        assertEquals("trailing-data", run(gz("more.nii.gz", more), "img").get("error"));

        // gzip として壊れている
        Path broken = inDir.resolve("broken.nii.gz");
        Files.write(broken, "this is not gzip".getBytes());
        assertEquals("not-nifti", run(broken, "img").get("error"));
        // gzip の途中で切れている
        byte[] full = Files.readAllBytes(gz("full.nii.gz", good));
        Path half = inDir.resolve("half.nii.gz");
        Files.write(half, java.util.Arrays.copyOf(full, full.length / 2));
        Object e = run(half, "img").get("error");
        assertTrue("truncated".equals(e) || "not-nifti".equals(e), String.valueOf(e));
        // 失敗の後に成果物のフォルダが残らない（成功した good の 1 つ分だけ）
        assertEquals(1, outputDirs());
    }

    @Test
    void huge_dim_is_refused_before_copying_anything() throws Exception {
        // 32767^3 × 4 バイトの宣言（実データは数バイト）。巨大な出力・確保をしない
        byte[] raw = NiftiFixtures.nifti(new int[]{32767, 32767, 32767}, new float[1], 16, 32, 352f, false);
        long t0 = System.nanoTime();
        assertEquals("too-large", run(gz("huge.nii.gz", raw), "img").get("error"));
        assertEquals("too-large", run(gz("huge.nii.gz", raw), "hdr").get("error"));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000);
        assertTrue(noOutputs());
    }

    @Test
    void input_outside_the_allowed_folder_or_not_a_regular_nii_gz_is_refused() throws Exception {
        byte[] raw = NiftiFixtures.nifti(new int[]{2, 2, 2}, NiftiFixtures.ramp(8), 16, 32, 352f, false);
        // 正例: 許可フォルダの下は通る
        assertNotNull(run(gz("in.nii.gz", raw), "hdr").get("__artifact"));
        // 負例 1: 一時フォルダの下でも、許可フォルダの外
        Path outside = tmp.resolve("outside.nii.gz");
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(outside))) {
            os.write(raw);
        }
        assertEquals("bad-input-path", run(outside, "hdr").get("error"));
        // 負例 2: .. で抜ける
        Path viaDots = inDir.resolve("..").resolve("..").resolve("outside.nii.gz");
        assertEquals("bad-input-path", run(viaDots, "hdr").get("error"));
        // 負例 3: シンボリックリンク（許可フォルダの中から外のファイルを指す）
        Path link = inDir.resolve("link.nii.gz");
        try {
            Files.createSymbolicLink(link, outside);
            assertEquals("bad-input-path", run(link, "hdr").get("error"));
        } catch (UnsupportedOperationException | IOException e) {
            // シンボリックリンクを作れない環境（権限のない Windows など）ではこの項目だけ飛ばす
        }
        // 負例 4: 拡張子違い・無いファイル・空
        assertEquals("bad-input-path", run(Files.write(inDir.resolve("x.txt"), raw), "hdr").get("error"));
        assertEquals("bad-input-path", run(inDir.resolve("none.nii.gz"), "hdr").get("error"));
        assertEquals("bad-input-path", plugin.run(Map.of("op", "convert", "part", "hdr")) instanceof Map<?, ?> m ? m.get("error") : null);
    }

    @Test
    void unknown_op_and_part_and_cancel() throws Exception {
        byte[] raw = NiftiFixtures.nifti(new int[]{8, 8, 8}, NiftiFixtures.ramp(512), 16, 32, 352f, false);
        Path in = gz("c.nii.gz", raw);
        assertEquals("unknown-op", ((Map<?, ?>) plugin.run(Map.of("op", "nope"))).get("error"));
        assertEquals("bad-part", run(in, "zip").get("error"));
        // 取り消し: すぐ止まり、書きかけとフォルダを残さない。取り消さなければ最後まで書く（対）
        Object cancelled = plugin.run(Map.of("op", "convert", "part", "img", "path", in.toString(),
                "__cancelled", (java.util.function.BooleanSupplier) () -> true));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) cancelled).get("cancelled"));
        assertEquals(0, outputDirs());
        Object ok = plugin.run(Map.of("op", "convert", "part", "img", "path", in.toString(),
                "__cancelled", (java.util.function.BooleanSupplier) () -> false));
        assertNotNull(((Map<?, ?>) ok).get("__artifact"));
    }

    @Test
    void progress_reaches_the_callback_for_large_images() throws Exception {
        // 64 MiB 以上の画素で進み具合が複数回出る（小さい画像では 1 回だけ）
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int[] dim = {256, 256, 300};
        long voxels = 256L * 256 * 300;
        ByteBuffer head = ByteBuffer.wrap(NiftiFixtures.nifti(dim, new float[0], 16, 32, 352f, false)).order(ByteOrder.LITTLE_ENDIAN);
        raw.write(head.array(), 0, 352);
        byte[] chunk = new byte[1 << 20];
        long left = voxels * 4;
        while (left > 0) {
            int n = (int) Math.min(chunk.length, left);
            raw.write(chunk, 0, n);
            left -= n;
        }
        Path in = gz("big.nii.gz", raw.toByteArray());
        List<Double> seen = new java.util.ArrayList<>();
        plugin.run(Map.of("op", "convert", "part", "img", "path", in.toString(),
                "__progress", (java.util.function.BiConsumer<Double, String>) (p, m) -> seen.add(p)));
        assertTrue(seen.size() >= 2, seen.toString());
        assertEquals(1.0, seen.get(seen.size() - 1), 1e-9);
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

    private boolean noOutputs() throws IOException {
        // 断った入力からは出力フォルダが 1 つも増えていない（先に成功した入力の分だけがある）
        return outputDirs() <= 1;
    }
}
