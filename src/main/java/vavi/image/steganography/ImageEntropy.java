/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.image.steganography;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.Arrays;


/**
 * Image information entropy calculation and 16x16 grid entropy matrix extraction.
 * <p>
 * Based on "A Modification-Free Steganography Method Based on Image Information Entropy"
 * (Shuang-Kui Xia and Jianbin Wu, Security and Communication Networks, 2018,
 * DOI: 10.1155/2018/6256872).
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-18 nsano initial version <br>
 */
public final class ImageEntropy {

    /** Default grid size (16 x 16 grid description of image). */
    public static final int DEFAULT_GRID_SIZE = 16;

    /** Number of grayscale levels. */
    private static final int GRAY_LEVELS = 256;

    /** Natural log of 2 for base-2 logarithm computation. */
    private static final double LN2 = Math.log(2.0);

    private ImageEntropy() {
    }

    /**
     * Converts a {@link BufferedImage} into a 2D array of grayscale intensity values in [0, 255].
     *
     * @param image the input image
     * @return a {@code height x width} array of grayscale values in [0, 255]
     */
    public static int[][] toGrayscale(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[][] gray = new int[height][width];

        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            Raster raster = image.getRaster();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    gray[y][x] = raster.getSample(x, y, 0) & 0xff;
                }
            }
        } else {
            int[] rgbRow = new int[width];
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, rgbRow, 0, width);
                for (int x = 0; x < width; x++) {
                    int argb = rgbRow[x];
                    int r = (argb >> 16) & 0xff;
                    int g = (argb >> 8) & 0xff;
                    int b = argb & 0xff;
                    // ITU-R BT.601 luminance
                    int lum = (77 * r + 150 * g + 29 * b + 128) >> 8;
                    gray[y][x] = Math.clamp(lum, 0, 255);
                }
            }
        }
        return gray;
    }

    /**
     * Computes the global Shannon information entropy of an image:
     * <pre>
     * H = -sum_{i=0}^{255} p(i) log_2 p(i)
     * </pre>
     *
     * @param image the input image
     * @return the global information entropy in bits [0.0, 8.0]
     */
    public static double computeGlobalEntropy(BufferedImage image) {
        int[][] gray = toGrayscale(image);
        return computeBlockEntropy(gray, 0, 0, image.getWidth(), image.getHeight());
    }

    /**
     * Computes the Shannon information entropy of a rectangular sub-block of grayscale pixels.
     *
     * @param gray the 2D grayscale array {@code [height][width]}
     * @param startX starting X coordinate (inclusive)
     * @param startY starting Y coordinate (inclusive)
     * @param endX ending X coordinate (exclusive)
     * @param endY ending Y coordinate (exclusive)
     * @return the Shannon entropy of the sub-block in bits [0.0, 8.0]
     */
    public static double computeBlockEntropy(int[][] gray, int startX, int startY, int endX, int endY) {
        int width = endX - startX;
        int height = endY - startY;
        int totalPixels = width * height;
        if (totalPixels <= 0) {
            return 0.0;
        }

        int[] histogram = new int[GRAY_LEVELS];
        for (int y = startY; y < endY; y++) {
            int[] row = gray[y];
            for (int x = startX; x < endX; x++) {
                histogram[row[x]]++;
            }
        }

        double entropy = 0.0;
        double invTotal = 1.0 / totalPixels;
        for (int i = 0; i < GRAY_LEVELS; i++) {
            int count = histogram[i];
            if (count > 0) {
                double p = count * invTotal;
                entropy -= p * (Math.log(p) / LN2);
            }
        }
        return entropy;
    }

    /**
     * Partitions the image into the default 16 x 16 grid and computes the 16 x 16
     * information entropy matrix.
     *
     * @param image the input image
     * @return a 16 x 16 matrix of local Shannon information entropy values
     */
    public static double[][] computeEntropyMatrix(BufferedImage image) {
        return computeEntropyMatrix(image, DEFAULT_GRID_SIZE);
    }

    /**
     * Partitions the image into an {@code gridSize x gridSize} grid and computes
     * the local Shannon information entropy for each sub-block.
     *
     * @param image the input image
     * @param gridSize the number of grid divisions along each axis (e.g., 16)
     * @return a {@code gridSize x gridSize} matrix of local Shannon entropy values
     */
    public static double[][] computeEntropyMatrix(BufferedImage image, int gridSize) {
        if (gridSize <= 0) {
            throw new IllegalArgumentException("gridSize must be positive: " + gridSize);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < gridSize || height < gridSize) {
            throw new IllegalArgumentException(
                    "Image dimensions (" + width + "x" + height + ") must be at least " + gridSize + "x" + gridSize);
        }

        int[][] gray = toGrayscale(image);
        double[][] matrix = new double[gridSize][gridSize];

        for (int r = 0; r < gridSize; r++) {
            int startY = (r * height) / gridSize;
            int endY = ((r + 1) * height) / gridSize;
            for (int c = 0; c < gridSize; c++) {
                int startX = (c * width) / gridSize;
                int endX = ((c + 1) * width) / gridSize;
                matrix[r][c] = computeBlockEntropy(gray, startX, startY, endX, endY);
            }
        }
        return matrix;
    }

    /**
     * Extracts normalized soft entropy features from the {@code N x N} entropy matrix.
     * <p>
     * Each entry in the returned array of length {@code N * N} is the zero-mean,
     * unit-variance normalized entropy difference {@code (H(r, c) - mean) / std}.
     * </p>
     *
     * @param entropyMatrix the {@code N x N} entropy matrix
     * @return normalized soft entropy values in row-major order
     */
    public static double[] extractSoftFeatures(double[][] entropyMatrix) {
        int n = entropyMatrix.length;
        int total = n * n;
        double[] flat = new double[total];
        double sum = 0.0;
        int idx = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                double v = entropyMatrix[r][c];
                flat[idx++] = v;
                sum += v;
            }
        }
        double mean = sum / total;
        double varSum = 0.0;
        for (int i = 0; i < total; i++) {
            double d = flat[i] - mean;
            varSum += d * d;
        }
        double std = Math.sqrt(varSum / total);
        if (std < 1e-9) {
            std = 1.0;
        }

        double[] soft = new double[total];
        for (int i = 0; i < total; i++) {
            soft[i] = (flat[i] - mean) / std;
        }
        return soft;
    }

    /**
     * Extracts binary feature bits (0 or 1) from the {@code N x N} entropy matrix
     * by thresholding each sub-block's entropy against the mean block entropy.
     *
     * @param entropyMatrix the {@code N x N} entropy matrix
     * @return a {@code byte[]} of length {@code N * N} containing 0 or 1 in row-major order
     */
    public static byte[] extractFeatureBits(double[][] entropyMatrix) {
        double[] soft = extractSoftFeatures(entropyMatrix);
        byte[] bits = new byte[soft.length];
        for (int i = 0; i < soft.length; i++) {
            bits[i] = (byte) (soft[i] >= 0.0 ? 1 : 0);
        }
        return bits;
    }

    /**
     * Computes the largest eigenvalues of the symmetrized entropy matrix
     * {@code S = (E + E^T) / 2} using the Jacobi eigenvalue algorithm.
     *
     * @param entropyMatrix the square entropy matrix
     * @param count number of largest eigenvalues to return
     * @return the {@code count} largest eigenvalues sorted in descending order
     */
    public static double[] extractLargestEigenvalues(double[][] entropyMatrix, int count) {
        int n = entropyMatrix.length;
        if (count <= 0 || count > n) {
            throw new IllegalArgumentException("count must be in [1, " + n + "]: " + count);
        }

        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[i][j] = 0.5 * (entropyMatrix[i][j] + entropyMatrix[j][i]);
            }
        }

        // Jacobi eigenvalue iterations for real symmetric matrix
        int maxSweeps = 50;
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            double offDiag = 0.0;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    offDiag += a[i][j] * a[i][j];
                }
            }
            if (offDiag < 1e-18) {
                break;
            }

            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    double apq = a[p][q];
                    if (Math.abs(apq) < 1e-12) {
                        continue;
                    }
                    double app = a[p][p];
                    double aqq = a[q][q];
                    double tau = (aqq - app) / (2.0 * apq);
                    double t = (tau >= 0.0)
                            ? 1.0 / (tau + Math.sqrt(1.0 + tau * tau))
                            : -1.0 / (-tau + Math.sqrt(1.0 + tau * tau));
                    double c = 1.0 / Math.sqrt(1.0 + t * t);
                    double s = t * c;

                    a[p][p] = app - t * apq;
                    a[q][q] = aqq + t * apq;
                    a[p][q] = 0.0;
                    a[q][p] = 0.0;

                    for (int r = 0; r < n; r++) {
                        if (r != p && r != q) {
                            double arp = a[r][p];
                            double arq = a[r][q];
                            a[r][p] = c * arp - s * arq;
                            a[p][r] = a[r][p];
                            a[r][q] = s * arp + c * arq;
                            a[q][r] = a[r][q];
                        }
                    }
                }
            }
        }

        double[] eigenvalues = new double[n];
        for (int i = 0; i < n; i++) {
            eigenvalues[i] = a[i][i];
        }
        Arrays.sort(eigenvalues);

        double[] largest = new double[count];
        for (int i = 0; i < count; i++) {
            largest[i] = eigenvalues[n - 1 - i];
        }
        return largest;
    }

    /**
     * Quantizes eigenvalues into 4-bit integers in [0, 15] via
     * {@code mod(round(lambda_i), 16)}.
     *
     * @param eigenvalues the extracted eigenvalues
     * @return quantized 4-bit integer values in [0, 15]
     */
    public static int[] quantizeEigenvalues(double[] eigenvalues) {
        int[] quantized = new int[eigenvalues.length];
        for (int i = 0; i < eigenvalues.length; i++) {
            long rounded = Math.round(eigenvalues[i]);
            quantized[i] = (int) Math.floorMod(rounded, 16);
        }
        return quantized;
    }
}
