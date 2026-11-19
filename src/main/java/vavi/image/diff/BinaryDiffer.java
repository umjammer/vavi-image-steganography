/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vavi.image.diff;

import java.awt.image.BufferedImage;


/**
 * A differ based on simple binary algorithm.
 * Simply compares the two images' pixels as is.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-19 nsano initial version <br>
 */
public class BinaryDiffer implements Differ {

    @Override
    public Result compare(BufferedImage a, BufferedImage b) {
        Differ.checkSize(a, b);
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = a.getRGB(0, 0, w, h, null, 0, w);
        int[] pb = b.getRGB(0, 0, w, h, null, 0, w);
        int[] pd = new int[w * h];
        int n = 0;
        for (int i = 0; i < pd.length; i++) {
            if (diffColor(pa[i], pb[i]) > 0) {
                pd[i] = DIFF;
                n++;
            } else {
                pd[i] = SAME;
            }
        }
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        diff.setRGB(0, 0, w, h, pd, 0, w);
        return new Result(diff, n);
    }

    /** @return sum of absolute differences of alpha-premultiplied 16bit components */
    static long diffColor(int c1, int c2) {
        if (c1 == c2) {
            return 0;
        }
        int[] a = Differ.rgba16(c1);
        int[] b = Differ.rgba16(c2);
        long diff = 0;
        for (int i = 0; i < 4; i++) {
            diff += Math.abs(a[i] - b[i]);
        }
        return diff;
    }
}
