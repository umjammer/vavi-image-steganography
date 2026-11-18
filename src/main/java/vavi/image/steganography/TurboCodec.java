/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.image.steganography;

import java.util.Arrays;
import java.util.Random;


/**
 * Parallel Concatenated Recursive Systematic Convolutional (RSC) Turbo Code
 * encoder and iterative Log-MAP (BCJR) soft-input/soft-output decoder.
 * <p>
 * Used in the Modification-Free Steganography Algorithm (MFSA) to provide high
 * data recovery rates (&gt;95%) under dimension scaling, rotation, and noise attacks
 * as described in Shuang-Kui Xia and Jianbin Wu (2018), DOI: 10.1155/2018/6256872.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-18 nsano initial version <br>
 */
public final class TurboCodec {

    /** Number of states in the 4-state (memory-2) RSC component encoder (7, 5)_8. */
    private static final int NUM_STATES = 4;

    /** Default number of Log-MAP decoding iterations. */
    public static final int DEFAULT_ITERATIONS = 6;

    /** Extrinsic LLR scaling/damping factor for iterative stability. */
    private static final double EXTRINSIC_SCALE = 0.75;

    /** Negative infinity proxy for log-domain trellis metrics. */
    private static final double NEG_INF = -1e9;

    /** Fixed interleaver seed for deterministic permutation. */
    private static final long INTERLEAVER_SEED = 0x5A17_6256_8720_2018L;

    /** Precomputed next state table: NEXT_STATE[state][inputBit]. */
    private static final int[][] NEXT_STATE = new int[NUM_STATES][2];

    /** Precomputed parity output table: PARITY_OUT[state][inputBit]. */
    private static final int[][] PARITY_OUT = new int[NUM_STATES][2];

    static {
        // Octal generators (G0 = 7_8 = 1 + D + D^2, G1 = 5_8 = 1 + D^2)
        for (int s = 0; s < NUM_STATES; s++) {
            int s1 = (s >> 1) & 1;
            int s2 = s & 1;
            for (int u = 0; u < 2; u++) {
                int d = u ^ s1 ^ s2;
                int p = d ^ s2;
                int nextS = (d << 1) | s1;
                NEXT_STATE[s][u] = nextS;
                PARITY_OUT[s][u] = p;
            }
        }
    }

    private TurboCodec() {
    }

    /**
     * Converts a byte array into an array of bits (0 or 1), MSB first per byte.
     *
     * @param bytes the input bytes
     * @return bit array of length {@code bytes.length * 8}
     */
    public static byte[] bytesToBits(byte[] bytes) {
        byte[] bits = new byte[bytes.length * 8];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            for (int j = 0; j < 8; j++) {
                bits[i * 8 + j] = (byte) ((b >> (7 - j)) & 1);
            }
        }
        return bits;
    }

    /**
     * Converts an array of bits (0 or 1), MSB first per byte, back into a byte array.
     *
     * @param bits the input bit array (length must be a multiple of 8)
     * @return reconstructed byte array
     */
    public static byte[] bitsToBytes(byte[] bits) {
        if (bits.length % 8 != 0) {
            throw new IllegalArgumentException("Bit array length must be a multiple of 8: " + bits.length);
        }
        byte[] bytes = new byte[bits.length / 8];
        for (int i = 0; i < bytes.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) {
                b = (b << 1) | (bits[i * 8 + j] & 1);
            }
            bytes[i] = (byte) b;
        }
        return bytes;
    }

    /**
     * Builds a deterministic pseudo-random interleaver permutation of length {@code length}.
     *
     * @param length number of information bits
     * @return permutation array {@code pi} where {@code interleaved[i] = input[pi[i]]}
     */
    public static int[] createInterleaver(int length) {
        int[] pi = new int[length];
        for (int i = 0; i < length; i++) {
            pi[i] = i;
        }
        Random rng = new Random(INTERLEAVER_SEED ^ length);
        for (int i = length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = pi[i];
            pi[i] = pi[j];
            pi[j] = tmp;
        }
        return pi;
    }

    /**
     * Encodes an array of information bits (0 or 1) using the rate-1/3 Turbo encoder.
     * <p>
     * For each information bit {@code u_k}, outputs 3 coded bits:
     * {@code [u_k, p1_k, p2_k]}, where {@code p1_k} is the parity bit from RSC1
     * and {@code p2_k} is the parity bit from RSC2 on the interleaved sequence.
     * </p>
     *
     * @param infoBits array of information bits (each 0 or 1)
     * @return encoded bit array of length {@code infoBits.length * 3}
     */
    public static byte[] encodeBits(byte[] infoBits) {
        int n = infoBits.length;
        int[] pi = createInterleaver(n);

        byte[] p1 = encodeRsc(infoBits);

        byte[] interleaved = new byte[n];
        for (int i = 0; i < n; i++) {
            interleaved[i] = infoBits[pi[i]];
        }
        byte[] p2 = encodeRsc(interleaved);

        byte[] coded = new byte[n * 3];
        for (int i = 0; i < n; i++) {
            coded[i * 3] = (byte) (infoBits[i] & 1);
            coded[i * 3 + 1] = p1[i];
            coded[i * 3 + 2] = p2[i];
        }
        return coded;
    }

    /**
     * Encodes a byte payload using the rate-1/3 Turbo encoder.
     *
     * @param payload the raw secret bytes
     * @return encoded bit array of length {@code payload.length * 24}
     */
    public static byte[] encodeBytes(byte[] payload) {
        return encodeBits(bytesToBits(payload));
    }

    /**
     * Decodes a soft Log-Likelihood Ratio (LLR) stream of length {@code 3 * L}
     * using the iterative Log-MAP (BCJR) Turbo decoder.
     * <p>
     * Convention: {@code llr[j] > 0} indicates bit 1 is more likely,
     * {@code llr[j] < 0} indicates bit 0 is more likely.
     * </p>
     *
     * @param codedLlr soft channel LLRs of length {@code 3 * L}
     * @param iterations number of Turbo iterations
     * @return decoded information bits (each 0 or 1) of length {@code L}
     */
    public static byte[] decodeSoftBits(double[] codedLlr, int iterations) {
        if (codedLlr.length % 3 != 0) {
            throw new IllegalArgumentException("Coded LLR length must be a multiple of 3: " + codedLlr.length);
        }
        int n = codedLlr.length / 3;
        if (n == 0) {
            return new byte[0];
        }

        int[] pi = createInterleaver(n);
        int[] piInv = new int[n];
        for (int i = 0; i < n; i++) {
            piInv[pi[i]] = i;
        }

        double[] lSys = new double[n];
        double[] lPar1 = new double[n];
        double[] lPar2 = new double[n];
        for (int i = 0; i < n; i++) {
            lSys[i] = codedLlr[i * 3];
            lPar1[i] = codedLlr[i * 3 + 1];
            lPar2[i] = codedLlr[i * 3 + 2];
        }

        double[] lSysInterleaved = new double[n];
        for (int i = 0; i < n; i++) {
            lSysInterleaved[i] = lSys[pi[i]];
        }

        double[] apriori1 = new double[n];
        double[] apriori2 = new double[n];
        double[] post1 = new double[n];
        double[] post2 = new double[n];

        int numIters = Math.max(1, iterations);
        for (int iter = 0; iter < numIters; iter++) {
            // Component decoder 1
            bcjrLogMap(lSys, lPar1, apriori1, post1);
            for (int i = 0; i < n; i++) {
                double ext1 = (post1[i] - lSys[i] - apriori1[i]) * EXTRINSIC_SCALE;
                apriori2[i] = Math.clamp(ext1, -30.0, 30.0);
            }
            // Interleave apriori2
            double[] temp = Arrays.copyOf(apriori2, n);
            for (int i = 0; i < n; i++) {
                apriori2[i] = temp[pi[i]];
            }

            // Component decoder 2
            bcjrLogMap(lSysInterleaved, lPar2, apriori2, post2);
            for (int i = 0; i < n; i++) {
                double ext2 = (post2[i] - lSysInterleaved[i] - apriori2[i]) * EXTRINSIC_SCALE;
                apriori1[pi[i]] = Math.clamp(ext2, -30.0, 30.0);
            }
        }

        byte[] decoded = new byte[n];
        for (int i = 0; i < n; i++) {
            double finalLlr = post2[piInv[i]];
            decoded[i] = (byte) (finalLlr >= 0.0 ? 1 : 0);
        }
        return decoded;
    }

    /**
     * Decodes a soft LLR stream into a byte array using the default number of iterations.
     *
     * @param codedLlr soft channel LLRs of length {@code 24 * numBytes}
     * @return reconstructed byte array
     */
    public static byte[] decodeSoftBytes(double[] codedLlr) {
        return bitsToBytes(decodeSoftBits(codedLlr, DEFAULT_ITERATIONS));
    }

    /**
     * Decodes hard coded bits (0 or 1) using the Turbo decoder.
     *
     * @param codedBits hard coded bits of length {@code 3 * L}
     * @return decoded information bits of length {@code L}
     */
    public static byte[] decodeHardBits(byte[] codedBits) {
        double[] llr = new double[codedBits.length];
        for (int i = 0; i < codedBits.length; i++) {
            llr[i] = (codedBits[i] & 1) != 0 ? 4.0 : -4.0;
        }
        return decodeSoftBits(llr, DEFAULT_ITERATIONS);
    }

    /**
     * Runs a single RSC component encoder on input bits starting from state 0.
     */
    private static byte[] encodeRsc(byte[] bits) {
        int n = bits.length;
        byte[] parity = new byte[n];
        int state = 0;
        for (int i = 0; i < n; i++) {
            int u = bits[i] & 1;
            parity[i] = (byte) PARITY_OUT[state][u];
            state = NEXT_STATE[state][u];
        }
        return parity;
    }

    /**
     * Runs the Log-MAP (BCJR) algorithm for one RSC component decoder.
     */
    private static void bcjrLogMap(double[] lSys, double[] lPar, double[] lApriori, double[] lPost) {
        int n = lSys.length;
        double[][] alpha = new double[n + 1][NUM_STATES];
        double[][] beta = new double[n + 1][NUM_STATES];

        // Initialize forward state metrics (start at state 0)
        alpha[0][0] = 0.0;
        for (int s = 1; s < NUM_STATES; s++) {
            alpha[0][s] = NEG_INF;
        }

        // Forward recursion
        for (int k = 0; k < n; k++) {
            Arrays.fill(alpha[k + 1], NEG_INF);
            double sysPlusApriori = lSys[k] + lApriori[k];
            double par = lPar[k];
            for (int s = 0; s < NUM_STATES; s++) {
                double a = alpha[k][s];
                if (a <= NEG_INF / 2) {
                    continue;
                }
                for (int u = 0; u < 2; u++) {
                    int nextS = NEXT_STATE[s][u];
                    int p = PARITY_OUT[s][u];
                    double xu = (u == 1) ? 0.5 : -0.5;
                    double xp = (p == 1) ? 0.5 : -0.5;
                    double gamma = xu * sysPlusApriori + xp * par;
                    alpha[k + 1][nextS] = maxStar(alpha[k + 1][nextS], a + gamma);
                }
            }
        }

        // Initialize backward state metrics (open trellis termination: all final states equally valid)
        Arrays.fill(beta[n], 0.0);

        // Backward recursion and a-posteriori LLR computation
        for (int k = n - 1; k >= 0; k--) {
            Arrays.fill(beta[k], NEG_INF);
            double sysPlusApriori = lSys[k] + lApriori[k];
            double par = lPar[k];
            double maxU0 = NEG_INF;
            double maxU1 = NEG_INF;

            for (int s = 0; s < NUM_STATES; s++) {
                for (int u = 0; u < 2; u++) {
                    int nextS = NEXT_STATE[s][u];
                    int p = PARITY_OUT[s][u];
                    double xu = (u == 1) ? 0.5 : -0.5;
                    double xp = (p == 1) ? 0.5 : -0.5;
                    double gamma = xu * sysPlusApriori + xp * par;

                    double bNext = beta[k + 1][nextS];
                    beta[k][s] = maxStar(beta[k][s], bNext + gamma);

                    double metric = alpha[k][s] + gamma + bNext;
                    if (u == 1) {
                        maxU1 = maxStar(maxU1, metric);
                    } else {
                        maxU0 = maxStar(maxU0, metric);
                    }
                }
            }
            lPost[k] = maxU1 - maxU0;
        }
    }

    /**
     * Jacobian logarithm: {@code ln(e^a + e^b) = max(a, b) + ln(1 + e^{-|a - b|})}.
     */
    private static double maxStar(double a, double b) {
        if (a <= NEG_INF / 2) {
            return b;
        }
        if (b <= NEG_INF / 2) {
            return a;
        }
        return Math.max(a, b) + Math.log1p(Math.exp(-Math.abs(a - b)));
    }
}
