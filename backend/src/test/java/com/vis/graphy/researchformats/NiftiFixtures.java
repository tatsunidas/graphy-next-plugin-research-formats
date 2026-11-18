/*
 * Copyright (c) 2026 Visionary Imaging Services, Inc.
 * SPDX-License-Identifier: MIT
 */
package com.vis.graphy.researchformats;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/** テスト用の合成 NIfTI。本体の書き手と同じ並び（n+1・vox_offset 352・拡張なし・float32・LE）を、仕様のバイト位置から手で組む。 */
final class NiftiFixtures {

    private NiftiFixtures() {
    }

    /** 本体の書き手と同じ並びの NIfTI-1（n+1・vox_offset 352・拡張なし・float32・LE）を組む。 */
    static byte[] nifti(int[] dim, float[] data, int datatype, int bitpix, float voxOffset, boolean ext) {
        int dims = dim.length;
        ByteBuffer b = ByteBuffer.allocate(352 + data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 348);
        b.putShort(40, (short) dims);
        for (int i = 0; i < dims; i++) {
            b.putShort(42 + 2 * i, (short) dim[i]);
        }
        for (int i = dims; i < 7; i++) {
            b.putShort(42 + 2 * i, (short) 1);
        }
        b.putShort(70, (short) datatype);
        b.putShort(72, (short) bitpix);
        b.putFloat(76, -1f);                    // pixdim[0] = qfac
        b.putFloat(80, 0.8f).putFloat(84, 0.6f).putFloat(88, 2.5f);
        b.putFloat(108, voxOffset);
        b.putShort(254, (short) 1);              // sform_code
        b.putFloat(280, -0.8f).putFloat(300, -0.6f).putFloat(320, 2.5f); // srow の対角（テスト用の目印）
        b.putFloat(284, 12.25f);                 // srow_x[3]
        b.put(344, (byte) 'n').put(345, (byte) '+').put(346, (byte) '1').put(347, (byte) 0);
        if (ext) {
            b.put(348, (byte) 1);
        }
        for (int i = 0; i < data.length; i++) {
            b.putFloat(352 + 4 * i, data[i]);
        }
        return b.array();
    }

    static float[] ramp(int n) {
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            d[i] = i * 0.5f - 7f;
        }
        d[n / 2] = Float.NaN;
        return d;
    }

    static Path gz(Path dir, String name, byte[] raw) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(p))) {
            os.write(raw);
        }
        return p;
    }
}
