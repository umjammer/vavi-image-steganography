/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.image.steganography;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * EntropySteganographyOpTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-18 nsano initial version <br>
 */
class EntropySteganographyOpTest {

    /**
     * Generates a synthetic natural-like textured test image with distinct block entropy
     * distribution across a 16x16 grid (simulating natural "hot images" with varying
     * regional texture complexity).
     */
    private static BufferedImage createTexturedImage(int width, int height, int imageType) {
        BufferedImage img = new BufferedImage(width, height, imageType);
        Random blockRng = new Random(0x6256872L);
        double[][] blockComplexity = new double[16][16];
        double[][] blockFreq = new double[16][16];
        for (int r = 0; r < 16; r++) {
            for (int c = 0; c < 16; c++) {
                // Vary local contrast/modulation across blocks so each cell has a distinct entropy
                blockComplexity[r][c] = (r * 16 + c) % 2 == 0
                        ? 8.0 + blockRng.nextDouble() * 12.0
                        : 140.0 + blockRng.nextDouble() * 100.0;
                blockFreq[r][c] = 3.0 + blockRng.nextDouble() * 7.0;
            }
        }

        for (int y = 0; y < height; y++) {
            double ny = (double) y / height;
            int gr = Math.min(15, (int) (ny * 16));
            for (int x = 0; x < width; x++) {
                double nx = (double) x / width;
                int gc = Math.min(15, (int) (nx * 16));

                // Smooth scale-invariant spatial pattern inside each grid region
                double lx = (nx * 16) - gc;
                double ly = (ny * 16) - gr;
                double amp = blockComplexity[gr][gc];
                double freq = blockFreq[gr][gc];

                double wave = 0.5
                        + 0.25 * Math.sin(2.0 * Math.PI * freq * lx + gr)
                        + 0.25 * Math.cos(2.0 * Math.PI * freq * ly + gc);
                int val = Math.clamp((int) Math.round(128.0 + (wave - 0.5) * amp), 0, 255);

                if (imageType == BufferedImage.TYPE_BYTE_GRAY) {
                    img.getRaster().setSample(x, y, 0, val);
                } else {
                    int r = Math.clamp(val + ((gr * 7) % 20 - 10), 0, 255);
                    int g = val;
                    int b = Math.clamp(val + ((gc * 11) % 20 - 10), 0, 255);
                    int argb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    img.setRGB(x, y, argb);
                }
            }
        }
        return img;
    }

    /**
     * Scales an image to {@code targetWidth x targetHeight} using bilinear interpolation.
     */
    private static BufferedImage scaleImage(BufferedImage src, int targetWidth, int targetHeight) {
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, src.getType());
        Graphics2D g2d = scaled.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g2d.dispose();
        }
        return scaled;
    }

    /**
     * Rotates an image around its center by {@code degrees} clockwise.
     */
    private static BufferedImage rotateImage(BufferedImage src, double degrees) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage rotated = new BufferedImage(w, h, src.getType());
        Graphics2D g2d = rotated.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            AffineTransform at = AffineTransform.getRotateInstance(Math.toRadians(degrees), w / 2.0, h / 2.0);
            g2d.drawRenderedImage(src, at);
        } finally {
            g2d.dispose();
        }
        return rotated;
    }

    @Test
    void testShannonEntropyAndGridMatrix() {
        // Constant image should have 0.0 bits of entropy
        BufferedImage uniform = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                uniform.getRaster().setSample(x, y, 0, 128);
            }
        }
        assertEquals(0.0, ImageEntropy.computeGlobalEntropy(uniform), 1e-9);

        // Uniform distribution over all 256 gray levels should have 8.0 bits of entropy
        BufferedImage fullSpectrum = new BufferedImage(256, 256, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                fullSpectrum.getRaster().setSample(x, y, 0, x);
            }
        }
        assertEquals(8.0, ImageEntropy.computeGlobalEntropy(fullSpectrum), 1e-9);

        // 16x16 entropy matrix extraction and eigenvalue quantization
        BufferedImage textured = createTexturedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        double[][] matrix = ImageEntropy.computeEntropyMatrix(textured);
        assertEquals(16, matrix.length);
        assertEquals(16, matrix[0].length);

        double[] eigenvalues = ImageEntropy.extractLargestEigenvalues(matrix, 8);
        assertEquals(8, eigenvalues.length);
        for (int i = 0; i < eigenvalues.length - 1; i++) {
            assertTrue(eigenvalues[i] >= eigenvalues[i + 1], "Eigenvalues must be sorted descending");
        }
        int[] quantized = ImageEntropy.quantizeEigenvalues(eigenvalues);
        assertEquals(8, quantized.length);
        for (int q : quantized) {
            assertTrue(q >= 0 && q < 16, "Quantized value must be in [0, 15]");
        }
    }

    @Test
    void testTurboCodecErrorCorrection() {
        byte[] payload = "AES-128-Key-Exch".getBytes(StandardCharsets.UTF_8);
        byte[] codedBits = TurboCodec.encodeBytes(payload);
        assertEquals(payload.length * 24, codedBits.length);

        // Corrupt ~8% of coded bits with low-confidence wrong-sign LLRs
        double[] noisyLlr = new double[codedBits.length];
        Random rng = new Random(42L);
        for (int i = 0; i < codedBits.length; i++) {
            double trueSign = codedBits[i] == 1 ? 1.0 : -1.0;
            if (rng.nextDouble() < 0.08) {
                noisyLlr[i] = -trueSign * 0.4; // bit flip near threshold
            } else {
                noisyLlr[i] = trueSign * 4.0;
            }
        }

        byte[] recovered = TurboCodec.decodeSoftBytes(noisyLlr);
        assertArrayEquals(payload, recovered);
    }

    @Test
    void testMappingKeySerialization() {
        byte[] bits = new byte[] {1, 0, 1, 1, 0, 0, 1, 0, 1, 1, 1};
        MappingKey key = new MappingKey(16, 123456789L, bits);

        byte[] serialized = key.toBytes();
        MappingKey fromBytes = MappingKey.fromBytes(serialized);
        assertEquals(key, fromBytes);

        String base64 = key.toBase64();
        MappingKey fromBase64 = MappingKey.fromBase64(base64);
        assertEquals(key, fromBase64);
    }

    @Test
    void testBufferedImageOpModificationFreeAndExactRoundTrip() {
        BufferedImage cover = createTexturedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        byte[] secret = "SymmetricKeyExchange:0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);

        // Encode via BufferedImageOp.filter
        EntropySteganographyOp encodeOp = new EntropySteganographyOp(secret);
        BufferedImage stego = encodeOp.filter(cover, null);

        assertNotNull(stego);
        assertEquals(cover.getWidth(), stego.getWidth());
        assertEquals(cover.getHeight(), stego.getHeight());

        // Verify 100% modification-free: every pixel in stego must equal cover
        for (int y = 0; y < cover.getHeight(); y++) {
            for (int x = 0; x < cover.getWidth(); x++) {
                assertEquals(cover.getRGB(x, y), stego.getRGB(x, y),
                        "Cover image must not be modified at (" + x + ", " + y + ")");
            }
        }

        // Verify BufferedImageOp contract methods
        Rectangle2D bounds = encodeOp.getBounds2D(cover);
        assertEquals(256.0, bounds.getWidth());
        assertEquals(256.0, bounds.getHeight());
        Point2D pt = encodeOp.getPoint2D(new Point2D.Double(12.5, 34.5), null);
        assertEquals(12.5, pt.getX());
        assertEquals(34.5, pt.getY());

        MappingKey mappingKey = encodeOp.getMappingKey();
        assertNotNull(mappingKey);

        // Decode via BufferedImageOp.filter from the unmodified cover image
        EntropySteganographyOp decodeOp = new EntropySteganographyOp(mappingKey);
        BufferedImage decodeOut = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        decodeOp.filter(stego, decodeOut);

        byte[] recovered = decodeOp.getSecret();
        assertNotNull(recovered);
        assertArrayEquals(secret, recovered);
        assertEquals(1.0, EntropySteganographyOp.calculateBitRecoveryRate(secret, recovered));
    }

    @Test
    void testDimensionScalingAttackAcrossPaperResolutions() {
        // Test across the 4 scales in Figure 9 of the paper: 1024x1024, 512x512, 256x256, 128x128
        BufferedImage cover1024 = createTexturedImage(1024, 1024, BufferedImage.TYPE_BYTE_GRAY);
        byte[] secretKey = "128BitSecretKey!".getBytes(StandardCharsets.UTF_8);

        EntropySteganographyOp encodeOp = new EntropySteganographyOp(secretKey);
        encodeOp.filter(cover1024, null);
        MappingKey key = encodeOp.getMappingKey();

        int[] targetScales = {1024, 512, 256, 128};
        for (int scale : targetScales) {
            BufferedImage scaledCover = (scale == 1024)
                    ? cover1024
                    : scaleImage(cover1024, scale, scale);

            EntropySteganographyOp decodeOp = new EntropySteganographyOp(key);
            decodeOp.filter(scaledCover, null);
            byte[] recovered = decodeOp.getSecret();

            double recoveryRate = EntropySteganographyOp.calculateBitRecoveryRate(secretKey, recovered);
            assertTrue(recoveryRate >= 0.95,
                    "Data recovery rate at scale " + scale + "x" + scale + " must exceed 95%, was: " + recoveryRate);
            assertArrayEquals(secretKey, recovered,
                    "Turbo code should achieve 100% recovery at scale " + scale + "x" + scale);
        }
    }

    @Test
    void testRotationAttacks() {
        BufferedImage cover = createTexturedImage(512, 512, BufferedImage.TYPE_BYTE_GRAY);
        byte[] secretKey = "StegoRotationKey".getBytes(StandardCharsets.UTF_8);

        MappingKey key = EntropySteganographyOp.encode(cover, secretKey);

        // Small-angle rotation attack (1.5 degrees) and cardinal rotations (90, 180, 270 degrees)
        double[] angles = {1.5, 90.0, 180.0, 270.0};
        for (double angle : angles) {
            BufferedImage rotated = rotateImage(cover, angle);
            byte[] recovered = EntropySteganographyOp.decode(rotated, key);
            double recoveryRate = EntropySteganographyOp.calculateBitRecoveryRate(secretKey, recovered);
            assertTrue(recoveryRate >= 0.95,
                    "Data recovery rate under " + angle + " deg rotation must exceed 95%, was: " + recoveryRate);
            assertArrayEquals(secretKey, recovered);
        }
    }
}
