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
import java.util.concurrent.CompletableFuture;


/**
 * A differ based on perceptual diff (pdiff) algorithm.
 * <p>
 * Uses a model of the human visual system (Laplacian pyramid, contrast sensitivity
 * function, visual masking, threshold vs intensity and CIE L*a*b* color test)
 * to decide whether pixels differ visibly.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-19 nsano initial version <br>
 * @see "Yee, Pattanaik, Greenberg, 'Spatiotemporal sensitivity and visual attention
 *      for efficient rendering of dynamic environments', 2001"
 */
public class PerceptualDiffer implements Differ {

    /** LAB colorspace */
    private static final double EPSILON = 216.0 / 24389.0;
    private static final double KAPPA = 24389.0 / 27.0;

    /** max levels */
    static final int LAP_LEVELS = 8;
    /** filter kernel */
    private static final double[] LAP_KERNEL = {0.05, 0.25, 0.4, 0.25, 0.05};

    /** white values of XYZ colorspace */
    private static final double WHITE_X, WHITE_Y, WHITE_Z;

    static {
        double[] w = xyz(0xffffffff, 1);
        WHITE_X = w[0];
        WHITE_Y = w[1];
        WHITE_Z = w[2];
    }

    private final double gamma;
    /** luminance */
    private final double lum;
    /** test for luminance only */
    private final boolean noColor;
    /** field of view */
    private final double fov;
    /** color factor */
    private final double cf;
    /** num one degree pixels */
    private final double odp;
    /** adaptation level index, starting from 0 */
    private final int ai;

    /**
     * Creates a perceptual differ with gamma = 2.2, luminance = 100.0,
     * fov = 45.0, cf = 1.0, noColor = false.
     */
    public PerceptualDiffer() {
        this(2.2, 100.0, 45.0, 1.0, false);
    }

    /**
     * @param gamma     gamma adjustment
     * @param luminance luminance factor
     * @param fov       field of view in degrees
     * @param cf        color factor
     * @param noColor   don't use color during comparison
     */
    public PerceptualDiffer(double gamma, double luminance, double fov, double cf, boolean noColor) {
        this.gamma = gamma;
        this.lum = luminance;
        this.fov = fov;
        this.cf = cf;
        this.noColor = noColor;
        this.odp = 2 * Math.tan(fov * 0.5 * Math.PI / 180) * 180 / Math.PI;
        int ai = 0;
        for (double n = 1.0; !(n > odp); n *= 2) {
            ai++;
            if (ai == LAP_LEVELS - 1) {
                break;
            }
        }
        this.ai = ai;
    }

    public double getGamma() { return gamma; }
    public double getLuminance() { return lum; }
    public double getFov() { return fov; }
    public double getColorFactor() { return cf; }
    public boolean isNoColor() { return noColor; }

    /** L*a*b* planes and luminance pyramid of an image */
    private record LabLap(double[] labA, double[] labB, double[][] lap) {}

    @Override
    public Result compare(BufferedImage a, BufferedImage b) {
        Differ.checkSize(a, b);
        int w = a.getWidth(), h = a.getHeight();

        CompletableFuture<LabLap> fa = CompletableFuture.supplyAsync(() -> labLap(a, gamma, lum));
        LabLap lb = labLap(b, gamma, lum);

        double[] cpd = new double[LAP_LEVELS]; // cycles per degree
        cpd[0] = 0.5 * w / odp; // 0.5 * pixels per degree
        for (int i = 1; i < LAP_LEVELS; i++) {
            cpd[i] = 0.5 * cpd[i - 1];
        }
        double csfMax = csf(3.248, 100.0);
        double[] freq = new double[LAP_LEVELS - 2];
        for (int i = 0; i < LAP_LEVELS - 2; i++) {
            freq[i] = csfMax / csf(cpd[i], 100.0);
        }

        LabLap la = fa.join();
        double[][] aLap = la.lap, bLap = lb.lap;

        double[] mask = new double[LAP_LEVELS - 2];
        double[] contrast = new double[LAP_LEVELS - 2];
        int[] pd = new int[w * h];
        int npix = 0; // num of diff pixels
        for (int p = 0; p < pd.length; p++) {
            double adapt = Math.max(0.5 * (aLap[ai][p] + bLap[ai][p]), 1e-5);
            double contrastSum = 0;
            for (int i = 0; i < LAP_LEVELS - 2; i++) {
                double n1 = Math.abs(aLap[i][p] - aLap[i + 1][p]);
                double n2 = Math.abs(bLap[i][p] - bLap[i + 1][p]);
                double d1 = Math.abs(aLap[i + 2][p]);
                double d2 = Math.abs(bLap[i + 2][p]);
                double d = Math.max(d1, d2);
                contrast[i] = Math.max(n1, n2) / Math.max(d, 1e-5);
                mask[i] = vmask(contrast[i] * csf(cpd[i], adapt));
                contrastSum += contrast[i];
            }
            if (contrastSum < 1e-5) {
                contrastSum = 1e-5;
            }

            double factor = 0;
            for (int i = 0; i < LAP_LEVELS - 2; i++) {
                factor += contrast[i] * freq[i] * mask[i] / contrastSum;
            }
            if (factor < 1) {
                factor = 1;
            } else if (factor > 10) {
                factor = 10;
            }

            double delta = Math.abs(aLap[0][p] - bLap[0][p]);
            boolean pass = true;
            // pure luminance test
            if (delta > factor * tvi(adapt)) {
                pass = false;
            } else if (!noColor) {
                // CIE delta E test with modifications
                double cf = this.cf;
                // ramp down the color test in scotopic regions
                if (adapt < 10.0) {
                    // don't do color test at all
                    cf = 0.0;
                }
                double da = la.labA[p] - lb.labA[p];
                double db = la.labB[p] - lb.labB[p];
                if ((da * da + db * db) * cf > factor) {
                    pass = false;
                }
            }

            if (!pass) {
                npix++;
                pd[p] = DIFF;
            } else {
                pd[p] = SAME;
            }
        }

        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        diff.setRGB(0, 0, w, h, pd, 0, w);
        return new Result(diff, npix);
    }

    /** @return {L*, a*, b*} */
    static double[] lab(double x, double y, double z) {
        double[] r = {x / WHITE_X, y / WHITE_Y, z / WHITE_Z};
        double[] f = new double[3];
        for (int i = 0; i < 3; i++) {
            if (r[i] > EPSILON) {
                f[i] = Math.pow(r[i], 1.0 / 3.0);
            } else {
                f[i] = (KAPPA * r[i] + 16.0) / 116.0;
            }
        }
        return new double[] {
            116.0 * f[1] - 16.0,
            500.0 * (f[0] - f[1]),
            200.0 * (f[1] - f[2])
        };
    }

    /** @return {X, Y, Z} of the argb color */
    static double[] xyz(int argb, double gamma) {
        int[] c = Differ.rgba16(argb);
        double rg = Math.pow(c[0] / (double) 0xffff, gamma);
        double gg = Math.pow(c[1] / (double) 0xffff, gamma);
        double bg = Math.pow(c[2] / (double) 0xffff, gamma);
        double x = rg * 0.576700 + gg * 0.185556 + bg * 0.188212;
        double y = rg * 0.297361 + gg * 0.627355 + bg * 0.0752847;
        double z = rg * 0.0270328 + gg * 0.0706879 + bg * 0.991248;
        return new double[] {x, y, z};
    }

    private static LabLap labLap(BufferedImage m, double gamma, double lum) {
        int w = m.getWidth(), h = m.getHeight();
        int[] px = m.getRGB(0, 0, w, h, null, 0, w);
        double[] aLum = new double[w * h];
        double[] labA = new double[w * h];
        double[] labB = new double[w * h];
        for (int i = 0; i < px.length; i++) {
            double[] c = xyz(px[i], gamma);
            double[] l = lab(c[0], c[1], c[2]);
            labA[i] = l[1];
            labB[i] = l[2];
            aLum[i] = c[1] * lum;
        }
        return new LabLap(labA, labB, pyramid(aLum, w, h));
    }

    /**
     * Creates a Laplacian Pyramid out of the image m.
     *
     * @param m row-major w * h plane
     * @return [level][y * w + x] where level ranges from 0 to {@link #LAP_LEVELS} - 1.
     */
    static double[][] pyramid(double[] m, int w, int h) {
        double[][] p = new double[LAP_LEVELS][];
        // first level is a copy
        p[0] = m.clone();
        // next levels are convolution of the previous one
        for (int l = 1; l < LAP_LEVELS; l++) {
            double[] prev = p[l - 1];
            double[] cur = new double[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double sum = 0;
                    for (int i = -2; i <= 2; i++) {
                        int nx = mirror(x + i, w);
                        for (int j = -2; j <= 2; j++) {
                            int ny = mirror(y + j, h);
                            sum += LAP_KERNEL[i + 2] * LAP_KERNEL[j + 2] * prev[ny * w + nx];
                        }
                    }
                    cur[y * w + x] = sum;
                }
            }
            p[l] = cur;
        }
        return p;
    }

    /**
     * Mirrors an out of range index back into [0, n) the same way as the original.
     * Additionally clamped so that images narrower than the kernel do not overflow.
     */
    private static int mirror(int v, int n) {
        if (v < 0) {
            v = -v;
        }
        if (v >= n) {
            v = 2 * n - v - 1;
        }
        return Math.clamp(v, 0, n - 1);
    }

    /**
     * Computes the contrast sensitivity function (Barten SPIE 1989)
     * given the cycles per degree cpd and luminance lum.
     */
    static double csf(double cpd, double lum) {
        double a = 440.0 * Math.pow(1.0 + 0.7 / lum, -0.2);
        double b = 0.3 * Math.pow(1.0 + 100.0 / lum, 0.15);
        return a * cpd * Math.exp(-b * cpd) * Math.sqrt(1.0 + 0.06 * Math.exp(b * cpd));
    }

    /** Visual Masking from Daly 1993, computed from contrast c. */
    static double vmask(double c) {
        double a = Math.pow(392.498 * c, 0.7);
        double b = Math.pow(0.0153 * a, 4.0);
        return Math.pow(1.0 + b, 0.25);
    }

    /**
     * Threshold vs Intensity, computes the threshold of visibility
     * given the adaptation luminance al in candelas per square meter.
     * It is based on Ward Larson Siggraph 1997.
     */
    static double tvi(double al) {
        double r;
        al = Math.log10(al);
        if (al < -3.94) {
            r = -2.86;
        } else if (al < -1.44) {
            r = Math.pow(0.405 * al + 1.6, 2.18) - 2.86;
        } else if (al < -0.0184) {
            r = al - 0.395;
        } else if (al < 1.9) {
            r = Math.pow(0.249 * al + 0.65, 2.7) - 0.72;
        } else {
            r = al - 1.255;
        }
        return Math.pow(10.0, r);
    }
}
