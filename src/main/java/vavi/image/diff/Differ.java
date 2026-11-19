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
 * The image comparison interface. All supported algorithms implement it.
 * <p>
 * Java port of <a href="https://github.com/x1ddos/imgdiff">imgdiff</a>.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-19 nsano initial version <br>
 * @see BinaryDiffer
 * @see PerceptualDiffer
 */
public interface Differ {

    /**
     * The result of a comparison.
     *
     * @param image  the difference image, different pixels are opaque red, others opaque black
     * @param pixels the number of pixels that are different according to an algorithm
     */
    record Result(BufferedImage image, int pixels) {}

    /**
     * Compares images a and b, returning the resulting difference image
     * and the number of pixels that are different according to an algorithm.
     *
     * @throws SizeMismatchException when width or height of the images do not match
     */
    Result compare(BufferedImage a, BufferedImage b);

    /** Thrown when the two images under comparison have different sizes. */
    class SizeMismatchException extends IllegalArgumentException {
        public SizeMismatchException() {
            super("images have different sizes");
        }
    }

    /** Creates a new differ based on simple binary algorithm. */
    static Differ binary() {
        return new BinaryDiffer();
    }

    /** Creates a new differ based on perceptual diff algorithm with default parameters. */
    static Differ perceptual() {
        return new PerceptualDiffer();
    }

    /** Opaque red, used for different pixels. */
    int DIFF = 0xffff0000;
    /** Opaque black, used for same pixels. */
    int SAME = 0xff000000;

    /**
     * Checks sizes of the images.
     *
     * @throws SizeMismatchException when width or height of the images do not match
     */
    static void checkSize(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new SizeMismatchException();
        }
    }

    /**
     * Converts a non-premultiplied 8bit sRGB ARGB value into alpha-premultiplied
     * 16bit {r, g, b, a} components, in the manner of Go's {@code color.Color#RGBA()}.
     */
    static int[] rgba16(int argb) {
        int a = ((argb >>> 24) & 0xff) * 0x101;
        int r = ((argb >>> 16) & 0xff) * 0x101;
        int g = ((argb >>> 8) & 0xff) * 0x101;
        int b = (argb & 0xff) * 0x101;
        return new int[] {
            (int) ((long) r * a / 0xffff),
            (int) ((long) g * a / 0xffff),
            (int) ((long) b * a / 0xffff),
            a
        };
    }
}
