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
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * DifferTest. ported from imgdiff perceptual_test.go
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-19 nsano initial version <br>
 */
class DifferTest {

    static final Differ PDIFF = new PerceptualDiffer(2.2, 100.0, 45.0, 1.0, false);
    static final Differ BDIFF = new BinaryDiffer();

    static Stream<Arguments> compareSource() {
        return Stream.of(
            Arguments.of("aqsis_vase_ref.png", "aqsis_vase.png", PDIFF, 108),
            Arguments.of("bug1102605_ref.tif", "bug1102605.tif", PDIFF, 2198),
            Arguments.of("bug1471457_ref.tif", "bug1471457.tif", PDIFF, 7),
            Arguments.of("cam_mb_ref.tif", "cam_mb.tif", PDIFF, 0),
            Arguments.of("fish1.png", "fish2.png", PDIFF, 27324),
            Arguments.of("aqsis_vase_ref.png", "aqsis_vase.png", BDIFF, 14796),
            Arguments.of("bug1102605_ref.tif", "bug1102605.tif", BDIFF, 3207),
            Arguments.of("bug1471457_ref.tif", "bug1471457.tif", BDIFF, 35),
            Arguments.of("cam_mb_ref.tif", "cam_mb.tif", BDIFF, 7),
            Arguments.of("fish1.png", "fish2.png", BDIFF, 137671)
        );
    }

    @ParameterizedTest
    @MethodSource("compareSource")
    void testCompare(String img1, String img2, Differ d, int npix) throws Exception {
        BufferedImage a = readTestImage(img1);
        BufferedImage b = readTestImage(img2);
        Differ.Result r = d.compare(a, b);
        assertNotNull(r.image());
        assertEquals(a.getWidth(), r.image().getWidth());
        assertEquals(a.getHeight(), r.image().getHeight());
        assertTrue(r.pixels() <= npix, img1 + " " + d.getClass().getSimpleName() + ": n=" + r.pixels() + "; want n <= " + npix);
        // diff image red pixel count should match
        int red = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (r.image().getRGB(x, y) == Differ.DIFF) red++;
            }
        }
        assertEquals(r.pixels(), red);
    }

    @Test
    void testIdentical() throws Exception {
        BufferedImage a = readTestImage("fish1.png");
        assertEquals(0, PDIFF.compare(a, a).pixels());
        assertEquals(0, BDIFF.compare(a, a).pixels());
    }

    @Test
    void testSize() {
        BufferedImage a = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        BufferedImage b = new BufferedImage(10, 11, BufferedImage.TYPE_INT_ARGB);
        assertThrows(Differ.SizeMismatchException.class, () -> PDIFF.compare(a, b));
        assertThrows(Differ.SizeMismatchException.class, () -> BDIFF.compare(a, b));
    }

    @Test
    void testBinarySinglePixel() {
        BufferedImage a = new BufferedImage(5, 4, BufferedImage.TYPE_INT_ARGB);
        BufferedImage b = new BufferedImage(5, 4, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(3, 2, 0xff010000);
        Differ.Result r = BDIFF.compare(a, b);
        assertEquals(1, r.pixels());
        assertEquals(Differ.DIFF, r.image().getRGB(3, 2));
        assertEquals(Differ.SAME, r.image().getRGB(0, 0));
    }

    @Test
    void testBinaryPremultiplied() {
        // fully transparent pixels are equal regardless of their color (as Go's color.RGBA())
        BufferedImage a = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        BufferedImage b = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        a.setRGB(0, 0, 0x00ff0000);
        b.setRGB(0, 0, 0x0000ff00);
        assertEquals(0, BDIFF.compare(a, b).pixels());
    }

    @Test
    void testPerceptualVisible() {
        // a big black square on white is perceptually visible
        BufferedImage a = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        BufferedImage b = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                a.setRGB(x, y, 0xffffff);
                b.setRGB(x, y, x >= 16 && x < 48 && y >= 16 && y < 48 ? 0x000000 : 0xffffff);
            }
        }
        assertTrue(PDIFF.compare(a, b).pixels() > 0);
    }

    @Test
    void testPerceptualInvisible() {
        // 1 level difference of a single pixel is not visible
        BufferedImage a = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        BufferedImage b = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        b.setRGB(10, 10, 0x010101);
        assertEquals(1, BDIFF.compare(a, b).pixels());
        assertEquals(0, PDIFF.compare(a, b).pixels());
    }

    @Test
    void testTinyImage() {
        // smaller than the pyramid kernel
        BufferedImage a = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        BufferedImage b = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        b.setRGB(0, 0, 0xffffff);
        assertEquals(1, PDIFF.compare(a, b).pixels());
    }

    @Test
    void testFunctions() {
        double[] white = PerceptualDiffer.xyz(0xffffffff, 1);
        double[] l = PerceptualDiffer.lab(white[0], white[1], white[2]);
        assertEquals(100.0, l[0], 1e-9);
        assertEquals(0.0, l[1], 1e-9);
        assertEquals(0.0, l[2], 1e-9);
        assertEquals(1.0, PerceptualDiffer.vmask(0), 1e-12);
        assertEquals(0.0, PerceptualDiffer.csf(0, 100), 1e-12);
        assertEquals(Math.pow(10, 2 - 1.255), PerceptualDiffer.tvi(100), 1e-9);

        double[] m = new double[9 * 7];
        java.util.Arrays.fill(m, 3.0);
        double[][] p = PerceptualDiffer.pyramid(m, 9, 7);
        assertEquals(PerceptualDiffer.LAP_LEVELS, p.length);
        for (double[] level : p) {
            for (double v : level) {
                assertEquals(3.0, v, 1e-9); // kernel sums to 1
            }
        }
    }

    static BufferedImage readTestImage(String name) throws IOException {
        try (InputStream is = DifferTest.class.getResourceAsStream("/imgdiff/" + name)) {
            BufferedImage image = ImageIO.read(is);
            assertNotNull(image, name);
            return image;
        }
    }
}
