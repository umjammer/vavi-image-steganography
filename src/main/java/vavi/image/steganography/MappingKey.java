/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.image.steganography;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;


/**
 * Immutable mapping key representing the modification-free steganographic mapping
 * relationship between a cover image's 16x16 information entropy matrix and a
 * Turbo-coded secret payload.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-18 nsano initial version <br>
 */
public final class MappingKey {

    /** Magic header bytes "MFSA". */
    private static final int MAGIC = 0x4D465341;

    /** Current binary format version. */
    private static final byte VERSION = 1;

    private final int gridSize;
    private final long seed;
    private final int codedBitLength;
    private final byte[] mappingBits;

    /**
     * Constructs a new {@link MappingKey}.
     *
     * @param gridSize the grid dimension (e.g., 16 for a 16x16 grid)
     * @param seed the PRNG seed used for random number visualization mapping
     * @param mappingBits array of mapped bits (each 0 or 1)
     */
    public MappingKey(int gridSize, long seed, byte[] mappingBits) {
        if (gridSize <= 0) {
            throw new IllegalArgumentException("gridSize must be positive: " + gridSize);
        }
        Objects.requireNonNull(mappingBits, "mappingBits must not be null");
        this.gridSize = gridSize;
        this.seed = seed;
        this.codedBitLength = mappingBits.length;
        this.mappingBits = Arrays.copyOf(mappingBits, mappingBits.length);
    }

    /**
     * Returns the grid size used when partitioning the cover image (default 16).
     *
     * @return the grid size
     */
    public int getGridSize() {
        return gridSize;
    }

    /**
     * Returns the PRNG seed used for grid coordinate permutation.
     *
     * @return the seed
     */
    public long getSeed() {
        return seed;
    }

    /**
     * Returns the number of Turbo-coded mapping bits.
     *
     * @return the coded bit length
     */
    public int getCodedBitLength() {
        return codedBitLength;
    }

    /**
     * Returns a defensive copy of the mapping bits (each 0 or 1).
     *
     * @return the mapping bits
     */
    public byte[] getMappingBits() {
        return Arrays.copyOf(mappingBits, mappingBits.length);
    }

    /**
     * Serializes this {@link MappingKey} into a compact byte array.
     *
     * @return serialized bytes
     */
    public byte[] toBytes() {
        int packedByteLen = (codedBitLength + 7) / 8;
        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + 4 + 8 + 4 + packedByteLen);
        buf.putInt(MAGIC);
        buf.put(VERSION);
        buf.putInt(gridSize);
        buf.putLong(seed);
        buf.putInt(codedBitLength);

        byte[] packed = new byte[packedByteLen];
        for (int i = 0; i < codedBitLength; i++) {
            if ((mappingBits[i] & 1) != 0) {
                packed[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        buf.put(packed);
        return buf.array();
    }

    /**
     * Deserializes a {@link MappingKey} from a byte array produced by {@link #toBytes()}.
     *
     * @param data the serialized byte array
     * @return the deserialized {@link MappingKey}
     */
    public static MappingKey fromBytes(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length < 21) {
            throw new IllegalArgumentException("Invalid MappingKey data length: " + data.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException(String.format("Invalid MappingKey magic: 0x%08X", magic));
        }
        byte version = buf.get();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported MappingKey version: " + version);
        }
        int gridSize = buf.getInt();
        long seed = buf.getLong();
        int codedBitLength = buf.getInt();
        int packedByteLen = (codedBitLength + 7) / 8;
        if (buf.remaining() < packedByteLen) {
            throw new IllegalArgumentException("Truncated MappingKey payload");
        }
        byte[] packed = new byte[packedByteLen];
        buf.get(packed);

        byte[] bits = new byte[codedBitLength];
        for (int i = 0; i < codedBitLength; i++) {
            bits[i] = (byte) ((packed[i / 8] >> (7 - (i % 8))) & 1);
        }
        return new MappingKey(gridSize, seed, bits);
    }

    /**
     * Encodes this {@link MappingKey} as a Base64 string.
     *
     * @return Base64-encoded representation
     */
    public String toBase64() {
        return Base64.getEncoder().encodeToString(toBytes());
    }

    /**
     * Decodes a {@link MappingKey} from a Base64 string.
     *
     * @param base64 the Base64-encoded string
     * @return the decoded {@link MappingKey}
     */
    public static MappingKey fromBase64(String base64) {
        return fromBytes(Base64.getDecoder().decode(base64));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MappingKey that)) return false;
        return gridSize == that.gridSize
                && seed == that.seed
                && codedBitLength == that.codedBitLength
                && Arrays.equals(mappingBits, that.mappingBits);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(gridSize, seed, codedBitLength);
        result = 31 * result + Arrays.hashCode(mappingBits);
        return result;
    }
}
