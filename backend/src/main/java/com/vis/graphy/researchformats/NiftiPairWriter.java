/*
 * Copyright (c) 2026 Visionary Imaging Services, Inc.
 * SPDX-License-Identifier: MIT
 */
package com.vis.graphy.researchformats;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * 本体の「NIfTI で書き出し」（H78）が作る {@code .nii.gz}（NIfTI-1・リトルエンディアン・float32・単一ファイル）を、
 * 2 ファイル形式（{@code .hdr} ＋ {@code .img}）に直す。
 *
 * <p>幾何（LPS→RAS の反転）は本体が 1 か所で決めた後なので、ここでは<b>ヘッダの欄を 2 つ（magic と vox_offset）直すだけ</b>で、
 * 他の欄（dim・pixdim・srow・qform・datatype）は 1 バイトも変えない。画素は生の float32 をそのまま {@code .img} に流す。
 * 想定外の形（NIfTI-2・別の型・拡張つき・長さの食い違い）は、何も書かずに理由コードで断る。
 */
final class NiftiPairWriter {

    /** 断った理由。{@link #code()} が画面で訳すコード。 */
    static final class Refused extends Exception {
        private final String code;

        Refused(String code, String detail) {
            super(detail == null ? code : code + ": " + detail);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** 検査済みのヘッダ。{@code bytes} は元の 348 バイト（変更前）。 */
    record Header(byte[] bytes, int[] dim, int dims, long voxels, long dataBytes) {
        int frames() {
            return dims >= 4 ? dim[3] : 1;
        }
    }

    static final int HEADER_SIZE = 348;
    /** 単一ファイルのヘッダ 348 バイトの後ろの 4 バイト（拡張なしの印）。 */
    private static final int EXT_SIZE = 4;
    private static final int DATATYPE_FLOAT32 = 16;
    private static final int BUF = 1 << 20;
    /** 1 つの {@code .img} の上限（バイト）。壊れた dim で巨大な出力を作らないための安全弁。 */
    static final long MAX_DATA_BYTES = 16L << 30;

    private NiftiPairWriter() {
    }

    private static InputStream open(Path gz) throws IOException {
        return new GZIPInputStream(new BufferedInputStream(Files.newInputStream(gz), BUF), BUF);
    }

    /** 先頭の 352 バイトを読んで検査する。画素は読まない。 */
    static Header readHeader(Path niiGz) throws IOException, Refused {
        byte[] head = new byte[HEADER_SIZE + EXT_SIZE];
        try (InputStream in = open(niiGz)) {
            int n = in.readNBytes(head, 0, head.length);
            if (n < head.length) {
                throw new Refused("not-nifti", "ヘッダが短い");
            }
        } catch (java.io.EOFException e) {
            throw new Refused("truncated", "gzip が途中で切れている");
        } catch (ZipException e) {
            throw new Refused("not-nifti", "gzip として読めない");
        }
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != HEADER_SIZE) {
            // NIfTI-2（540）やビッグエンディアン（そのバイト列は 348 にならない）はここで断る
            throw new Refused("not-nifti1", "sizeof_hdr=" + b.getInt(0));
        }
        if (!(head[344] == 'n' && head[345] == '+' && head[346] == '1' && head[347] == 0)) {
            throw new Refused("not-nifti1", "magic が n+1 ではない");
        }
        if (b.getFloat(108) != HEADER_SIZE + EXT_SIZE) {
            throw new Refused("has-extension", "vox_offset=" + b.getFloat(108));
        }
        if (head[HEADER_SIZE] != 0 || head[HEADER_SIZE + 1] != 0 || head[HEADER_SIZE + 2] != 0 || head[HEADER_SIZE + 3] != 0) {
            throw new Refused("has-extension", "拡張がある");
        }
        if (b.getShort(70) != DATATYPE_FLOAT32 || b.getShort(72) != 32) {
            throw new Refused("unsupported-type", "datatype=" + b.getShort(70) + " bitpix=" + b.getShort(72));
        }
        int dims = b.getShort(40);
        if (dims < 3 || dims > 4) {
            throw new Refused("unsupported-dims", "dim[0]=" + dims);
        }
        int[] dim = new int[dims];
        long voxels = 1;
        for (int i = 0; i < dims; i++) {
            dim[i] = b.getShort(42 + 2 * i);
            if (dim[i] < 1) {
                throw new Refused("bad-dim", "dim[" + (i + 1) + "]=" + dim[i]);
            }
            voxels *= dim[i]; // 各 ≤ 32767 の 4 個の積は long に収まる
        }
        long dataBytes = voxels * 4;
        if (dataBytes > MAX_DATA_BYTES) {
            throw new Refused("too-large", dataBytes + " バイト");
        }
        byte[] orig = new byte[HEADER_SIZE];
        System.arraycopy(head, 0, orig, 0, HEADER_SIZE);
        return new Header(orig, dim, dims, voxels, dataBytes);
    }

    /** {@code .hdr}: 元のヘッダの magic を {@code ni1} に、vox_offset を 0 にしたもの（348 バイト）。 */
    static byte[] hdrBytes(Header h) {
        byte[] out = h.bytes().clone();
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putFloat(108, 0f);
        out[344] = 'n';
        out[345] = 'i';
        out[346] = '1';
        out[347] = 0;
        return out;
    }

    static void writeHdr(Path niiGz, Path out) throws IOException, Refused {
        Header h = readHeader(niiGz);
        Files.write(out, hdrBytes(h));
    }

    /**
     * {@code .img}: 画素を生のまま流す。長さが dim の積×4 と<b>ちょうど</b>一致しなければ断り、書きかけを消す。
     *
     * @param progress 0〜1（null 可）
     * @param cancelled 取り消し（null 可）。true を返したら止めて書きかけを消し、false を返す
     * @return 最後まで書いたら true、取り消しで止めたら false
     */
    static boolean writeImg(Path niiGz, Path out, java.util.function.DoubleConsumer progress,
                            java.util.function.BooleanSupplier cancelled) throws IOException, Refused {
        Header h = readHeader(niiGz);
        boolean done = false;
        try (InputStream in = open(niiGz); OutputStream os = Files.newOutputStream(out)) {
            long skipped = in.skip(HEADER_SIZE + EXT_SIZE);
            if (skipped != HEADER_SIZE + EXT_SIZE) {
                // GZIPInputStream.skip は短く返すことがある。読んで捨てる
                byte[] junk = new byte[HEADER_SIZE + EXT_SIZE];
                in.readNBytes(junk, 0, (int) (HEADER_SIZE + EXT_SIZE - Math.max(skipped, 0)));
            }
            byte[] buf = new byte[BUF];
            long left = h.dataBytes();
            long next = 0;
            while (left > 0) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return false;
                }
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new Refused("truncated", "画素が足りない（残り " + left + " バイト）");
                }
                os.write(buf, 0, n);
                left -= n;
                long written = h.dataBytes() - left;
                if (progress != null && written >= next) {
                    progress.accept((double) written / h.dataBytes());
                    next = written + (64L << 20);
                }
            }
            if (in.read() >= 0) {
                throw new Refused("trailing-data", "画素のあとにデータが続く");
            }
            if (progress != null) {
                progress.accept(1.0);
            }
            done = true;
        } catch (java.io.EOFException e) {
            throw new Refused("truncated", "gzip が途中で切れている");
        } catch (ZipException e) {
            throw new Refused("not-nifti", "gzip が壊れている");
        } finally {
            if (!done) {
                Files.deleteIfExists(out); // 断った・取り消した・壊れていたとき、書きかけを残さない
            }
        }
        return true;
    }
}
