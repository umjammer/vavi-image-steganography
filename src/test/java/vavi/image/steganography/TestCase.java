/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.image.steganography;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import vavi.awt.image.resample.AwtResampleOp;
import vavi.awt.image.resample.Lanczos3ResampleOp;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;


/**
 * TestCase.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 ${DATE} nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class TestCase {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property
    String image = "src/test/resources/test.png";

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    @Test
    void test1() throws Exception {
        BufferedImage cover = ImageIO.read(Files.newInputStream(Path.of(image)));
        byte[] secret = "SymmetricKeyExchange".getBytes(StandardCharsets.UTF_8);

        // Encode (modification-free: cover image pixels are not altered)
        EntropySteganographyOp encodeOp = new EntropySteganographyOp(secret);
        BufferedImage stego = encodeOp.filter(cover, null);
        MappingKey key = encodeOp.getMappingKey();

        AwtResampleOp resampleOp = new AwtResampleOp(.2, .2);
        BufferedImage modifiedStego = resampleOp.filter(stego, null);

        // Decode from unmodified (or scaled/rotated) cover image and MappingKey
        EntropySteganographyOp decodeOp = new EntropySteganographyOp(key);
        decodeOp.filter(modifiedStego, null);
        byte[] recovered = decodeOp.getSecret();

        assertArrayEquals(secret, recovered);
    }
}
