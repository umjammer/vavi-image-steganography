/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.image.steganography;

import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Objects;
import java.util.Random;


/**
 * Modification-free steganography entry point implementing {@link BufferedImageOp}.
 * <p>
 * Implements the Modification-Free Steganography Algorithm (MFSA) based on
 * image information entropy (16x16 grid partitioning) combined with Turbo codes,
 * as proposed in:
 * <blockquote>
 * Shuang-Kui Xia and Jianbin Wu, "A Modification-Free Steganography Method Based on
 * Image Information Entropy", <i>Security and Communication Networks</i>, vol. 2018,
 * Article ID 6256872, 2018. <a href="https://onlinelibrary.wiley.com/doi/10.1155/2018/6256872">DOI: 10.1155/2018/6256872</a>
 * </blockquote>
 * Because the algorithm is modification-free, {@link #filter(BufferedImage, BufferedImage)}
 * never alters any pixel of the cover image; instead, it constructs or reverses the
 * mapping relationship between the cover image's 16x16 information entropy matrix
 * and the Turbo-coded secret payload.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-18 nsano initial version <br>
 */
public class EntropySteganographyOp implements BufferedImageOp {

    /**
     * Operation mode for {@link EntropySteganographyOp}.
     */
    public enum Mode {
        /** Constructs a {@link MappingKey} from the cover image and secret payload. */
        ENCODE,
        /** Extracts the secret payload from the cover image and {@link MappingKey}. */
        DECODE
    }

    /** Default PRNG seed for grid coordinate permutation. */
    public static final long DEFAULT_SEED = 0x2018_6256_8720_4653L;

    /** Soft LLR scaling factor for normalized block entropy differences. */
    private static final double LLR_CHANNEL_SCALE = 4.0;

    /** Frame header size in bytes (2 bytes payload length + 2 bytes CRC-16). */
    private static final int HEADER_BYTES = 4;

    private final Mode mode;
    private final int gridSize;
    private final long seed;
    private final RenderingHints hints;

    private byte[] secret;
    private MappingKey mappingKey;

    /**
     * Creates an {@link EntropySteganographyOp} in {@link Mode#ENCODE} mode with the default
     * 16x16 grid and default seed.
     *
     * @param secret the secret payload bytes to map
     */
    public EntropySteganographyOp(byte[] secret) {
        this(secret, ImageEntropy.DEFAULT_GRID_SIZE, DEFAULT_SEED, null);
    }

    /**
     * Creates an {@link EntropySteganographyOp} in {@link Mode#ENCODE} mode.
     *
     * @param secret the secret payload bytes to map
     * @param gridSize the grid dimension (e.g., 16 for 16x16 grid)
     * @param seed the PRNG seed for coordinate permutation
     * @param hints optional rendering hints
     */
    public EntropySteganographyOp(byte[] secret, int gridSize, long seed, RenderingHints hints) {
        Objects.requireNonNull(secret, "secret must not be null");
        if (gridSize <= 0) {
            throw new IllegalArgumentException("gridSize must be positive: " + gridSize);
        }
        this.mode = Mode.ENCODE;
        this.secret = Arrays.copyOf(secret, secret.length);
        this.gridSize = gridSize;
        this.seed = seed;
        this.hints = hints;
    }

    /**
     * Creates an {@link EntropySteganographyOp} in {@link Mode#DECODE} mode using the given
     * {@link MappingKey}.
     *
     * @param mappingKey the mapping relationship key produced during encoding
     */
    public EntropySteganographyOp(MappingKey mappingKey) {
        this(mappingKey, null);
    }

    /**
     * Creates an {@link EntropySteganographyOp} in {@link Mode#DECODE} mode using the given
     * {@link MappingKey} and optional {@link RenderingHints}.
     *
     * @param mappingKey the mapping relationship key produced during encoding
     * @param hints optional rendering hints
     */
    public EntropySteganographyOp(MappingKey mappingKey, RenderingHints hints) {
        Objects.requireNonNull(mappingKey, "mappingKey must not be null");
        this.mode = Mode.DECODE;
        this.mappingKey = mappingKey;
        this.gridSize = mappingKey.getGridSize();
        this.seed = mappingKey.getSeed();
        this.hints = hints;
    }

    /**
     * Performs the modification-free steganographic operation on the source image.
     * <p>
     * In {@link Mode#ENCODE} mode, computes the cover image's 16x16 information entropy
     * matrix, encodes the secret payload with Turbo codes, and constructs the
     * {@link MappingKey} (retrievable via {@link #getMappingKey()}).
     * </p>
     * <p>
     * In {@link Mode#DECODE} mode, computes the received image's 16x16 information entropy
     * matrix, reverses the mapping using the {@link MappingKey}, and decodes the secret
     * payload via the iterative Log-MAP Turbo decoder (retrievable via {@link #getSecret()}).
     * </p>
     * <p>
     * In both modes, the cover image pixels are left completely unmodified: the returned
     * destination image is a pixel-identical copy of {@code src}.
     * </p>
     *
     * @param src the source {@link BufferedImage}
     * @param dst the destination {@link BufferedImage}, or {@code null}
     * @return the unmodified destination {@link BufferedImage}
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        Objects.requireNonNull(src, "src image must not be null");
        if (dst == null) {
            dst = createCompatibleDestImage(src, null);
        }
        if (dst != src) {
            copyImageUnmodified(src, dst);
        }

        if (mode == Mode.ENCODE) {
            this.mappingKey = encode(src, this.secret, this.gridSize, this.seed);
        } else {
            this.secret = decode(src, this.mappingKey);
        }
        return dst;
    }

    /**
     * Returns the operation mode ({@link Mode#ENCODE} or {@link Mode#DECODE}).
     *
     * @return the operation mode
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Returns the {@link MappingKey} generated after {@link #filter(BufferedImage, BufferedImage)}
     * in {@link Mode#ENCODE} mode (or supplied in {@link Mode#DECODE} mode).
     *
     * @return the {@link MappingKey}
     */
    public MappingKey getMappingKey() {
        return mappingKey;
    }

    /**
     * Returns the secret payload bytes (either supplied for {@link Mode#ENCODE} or
     * extracted after {@link #filter(BufferedImage, BufferedImage)} in {@link Mode#DECODE}).
     *
     * @return a defensive copy of the secret payload bytes, or {@code null} if not yet decoded
     */
    public byte[] getSecret() {
        return secret != null ? Arrays.copyOf(secret, secret.length) : null;
    }

    /**
     * Encodes (maps) a secret payload against an unmodified cover image using the default
     * 16x16 grid and default seed.
     *
     * @param cover the cover image (never modified)
     * @param secret the secret payload bytes
     * @return the generated {@link MappingKey}
     */
    public static MappingKey encode(BufferedImage cover, byte[] secret) {
        return encode(cover, secret, ImageEntropy.DEFAULT_GRID_SIZE, DEFAULT_SEED);
    }

    /**
     * Encodes (maps) a secret payload against an unmodified cover image.
     *
     * @param cover the cover image (never modified)
     * @param secret the secret payload bytes
     * @param gridSize the grid dimension (default 16)
     * @param seed the PRNG seed for grid coordinate mapping
     * @return the generated {@link MappingKey}
     */
    public static MappingKey encode(BufferedImage cover, byte[] secret, int gridSize, long seed) {
        Objects.requireNonNull(cover, "cover image must not be null");
        Objects.requireNonNull(secret, "secret must not be null");

        double[][] entropyMatrix = ImageEntropy.computeEntropyMatrix(cover, gridSize);
        byte[] featureBits = ImageEntropy.extractFeatureBits(entropyMatrix);

        byte[] framed = framePayload(secret);
        byte[] codedBits = TurboCodec.encodeBytes(framed);

        int[] gridIndices = generateGridSchedule(codedBits.length, featureBits.length, seed);
        byte[] mappingBits = new byte[codedBits.length];
        for (int k = 0; k < codedBits.length; k++) {
            int cell = gridIndices[k];
            mappingBits[k] = (byte) ((codedBits[k] ^ featureBits[cell]) & 1);
        }

        return new MappingKey(gridSize, seed, mappingBits);
    }

    /**
     * Decodes (extracts) the secret payload from a received cover image and {@link MappingKey}.
     * <p>
     * Uses soft-decision Log-MAP Turbo decoding weighted by normalized sub-block entropy
     * margins, and automatically checks 90/180/270-degree rotated entropy matrices if
     * the image underwent a cardinal rotation attack.
     * </p>
     *
     * @param receivedCover the received cover image
     * @param key the {@link MappingKey}
     * @return the recovered secret payload bytes
     */
    public static byte[] decode(BufferedImage receivedCover, MappingKey key) {
        Objects.requireNonNull(receivedCover, "receivedCover must not be null");
        Objects.requireNonNull(key, "key must not be null");

        int gridSize = key.getGridSize();
        byte[] mappingBits = key.getMappingBits();
        int expectedPayloadLen = (mappingBits.length / 24) - HEADER_BYTES;
        if (expectedPayloadLen < 0) {
            throw new IllegalArgumentException("Invalid MappingKey coded bit length: " + mappingBits.length);
        }

        double[][] baseEntropyMatrix = ImageEntropy.computeEntropyMatrix(receivedCover, gridSize);
        int[] gridIndices = generateGridSchedule(mappingBits.length, gridSize * gridSize, key.getSeed());

        byte[] bestCandidate = null;
        double[][] currentMatrix = baseEntropyMatrix;

        // Try 0, 90, 180, 270 degree orientations of the entropy matrix
        for (int rot = 0; rot < 4; rot++) {
            if (rot > 0) {
                currentMatrix = rotateMatrix90CW(currentMatrix);
            }
            double[] softFeatures = ImageEntropy.extractSoftFeatures(currentMatrix);
            double[] codedLlr = new double[mappingBits.length];
            for (int k = 0; k < mappingBits.length; k++) {
                int cell = gridIndices[k];
                double sign = (mappingBits[k] & 1) == 0 ? 1.0 : -1.0;
                double cellLlr = softFeatures[cell] * LLR_CHANNEL_SCALE;
                // Ensure non-zero magnitude even if a block sits exactly on the mean
                if (Math.abs(cellLlr) < 0.05) {
                    cellLlr = cellLlr >= 0.0 ? 0.05 : -0.05;
                }
                codedLlr[k] = sign * cellLlr;
            }

            byte[] decodedFramed = TurboCodec.decodeSoftBytes(codedLlr);
            byte[] verified = unframeAndVerify(decodedFramed, expectedPayloadLen);
            if (verified != null) {
                return verified;
            }
            if (rot == 0) {
                bestCandidate = Arrays.copyOfRange(decodedFramed, HEADER_BYTES, HEADER_BYTES + expectedPayloadLen);
            }
        }

        return bestCandidate;
    }

    /**
     * Computes the bit-level data recovery rate in [0.0, 1.0] between original and
     * recovered secret byte arrays.
     *
     * @param original the original secret payload
     * @param recovered the recovered secret payload
     * @return fraction of matching bits in [0.0, 1.0]
     */
    public static double calculateBitRecoveryRate(byte[] original, byte[] recovered) {
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(recovered, "recovered must not be null");
        int totalBits = original.length * 8;
        if (totalBits == 0) {
            return recovered.length == 0 ? 1.0 : 0.0;
        }
        int minBytes = Math.min(original.length, recovered.length);
        int matchingBits = 0;
        for (int i = 0; i < minBytes; i++) {
            int xor = (original[i] ^ recovered[i]) & 0xff;
            matchingBits += (8 - Integer.bitCount(xor));
        }
        return (double) matchingBits / totalBits;
    }

    @Override
    public Rectangle2D getBounds2D(BufferedImage src) {
        return new Rectangle2D.Float(0, 0, src.getWidth(), src.getHeight());
    }

    @Override
    public BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel destCM) {
        if (destCM == null) {
            destCM = src.getColorModel();
        }
        WritableRaster raster = destCM.createCompatibleWritableRaster(src.getWidth(), src.getHeight());
        return new BufferedImage(destCM, raster, destCM.isAlphaPremultiplied(), new Hashtable<>());
    }

    @Override
    public Point2D getPoint2D(Point2D srcPt, Point2D dstPt) {
        if (dstPt == null) {
            dstPt = new Point2D.Double();
        }
        dstPt.setLocation(srcPt.getX(), srcPt.getY());
        return dstPt;
    }

    @Override
    public RenderingHints getRenderingHints() {
        return hints;
    }

    /**
     * Copies {@code src} into {@code dst} without modifying any pixel values.
     */
    private static void copyImageUnmodified(BufferedImage src, BufferedImage dst) {
        int width = Math.min(src.getWidth(), dst.getWidth());
        int height = Math.min(src.getHeight(), dst.getHeight());
        if (src.getType() == dst.getType() && src.getType() != BufferedImage.TYPE_CUSTOM) {
            Object dataElements = null;
            WritableRaster srcRaster = src.getRaster();
            WritableRaster dstRaster = dst.getRaster();
            for (int y = 0; y < height; y++) {
                dataElements = srcRaster.getDataElements(0, y, width, 1, dataElements);
                dstRaster.setDataElements(0, y, width, 1, dataElements);
            }
        } else {
            int[] rgb = src.getRGB(0, 0, width, height, null, 0, width);
            dst.setRGB(0, 0, width, height, rgb, 0, width);
        }
    }

    /**
     * Generates a pseudo-random schedule of grid cell indices for {@code numCodedBits},
     * cycling through independent random permutations of {@code [0, numGridCells - 1]}.
     */
    private static int[] generateGridSchedule(int numCodedBits, int numGridCells, long seed) {
        int[] schedule = new int[numCodedBits];
        int[] perm = new int[numGridCells];
        for (int i = 0; i < numGridCells; i++) {
            perm[i] = i;
        }
        Random rng = new Random(seed);
        int pos = numGridCells;
        for (int k = 0; k < numCodedBits; k++) {
            if (pos >= numGridCells) {
                for (int i = numGridCells - 1; i > 0; i--) {
                    int j = rng.nextInt(i + 1);
                    int tmp = perm[i];
                    perm[i] = perm[j];
                    perm[j] = tmp;
                }
                pos = 0;
            }
            schedule[k] = perm[pos++];
        }
        return schedule;
    }

    /**
     * Prepends a 4-byte header (2-byte length + 2-byte CRC-16) to the secret payload.
     */
    private static byte[] framePayload(byte[] payload) {
        int len = payload.length;
        if (len > 0xFFFF) {
            throw new IllegalArgumentException("Payload too large (max 65535 bytes): " + len);
        }
        int crc = crc16(payload, 0, len);
        byte[] framed = new byte[HEADER_BYTES + len];
        framed[0] = (byte) ((len >> 8) & 0xff);
        framed[1] = (byte) (len & 0xff);
        framed[2] = (byte) ((crc >> 8) & 0xff);
        framed[3] = (byte) (crc & 0xff);
        System.arraycopy(payload, 0, framed, HEADER_BYTES, len);
        return framed;
    }

    /**
     * Verifies the 4-byte header (length and CRC-16) and extracts the payload, or
     * returns {@code null} if verification fails.
     */
    private static byte[] unframeAndVerify(byte[] framed, int expectedLen) {
        if (framed.length < HEADER_BYTES + expectedLen) {
            return null;
        }
        int len = ((framed[0] & 0xff) << 8) | (framed[1] & 0xff);
        if (len != expectedLen) {
            return null;
        }
        int expectedCrc = ((framed[2] & 0xff) << 8) | (framed[3] & 0xff);
        int actualCrc = crc16(framed, HEADER_BYTES, expectedLen);
        if (expectedCrc != actualCrc) {
            return null;
        }
        return Arrays.copyOfRange(framed, HEADER_BYTES, HEADER_BYTES + expectedLen);
    }

    /**
     * Rotates a square matrix 90 degrees clockwise.
     */
    private static double[][] rotateMatrix90CW(double[][] matrix) {
        int n = matrix.length;
        double[][] rotated = new double[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                rotated[c][n - 1 - r] = matrix[r][c];
            }
        }
        return rotated;
    }

    /**
     * Computes CRC-16-CCITT (polynomial 0x1021) over {@code data[offset..offset+len]}.
     */
    private static int crc16(byte[] data, int offset, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (data[offset + i] & 0xff) << 8;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc;
    }
}
