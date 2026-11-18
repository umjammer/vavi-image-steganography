[![Release](https://jitpack.io/v/umjammer/vavi-image-steganography.svg)](https://jitpack.io/#umjammer/vavi-image-steganography)
[![Java CI](https://github.com/umjammer/vavi-image-steganography/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-image-steganography/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-image-steganography/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-image-steganography/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-image-steganography

## Install

 * [maven](https://jitpack.io/#umjammer/vavi-image-steganography)

## Usage

```java
BufferedImage cover = ImageIO.read(...);
byte[] secret = "SymmetricKeyExchange".getBytes(StandardCharsets.UTF_8);

// Encode (modification-free: cover image pixels are not altered)
EntropySteganographyOp encodeOp = new EntropySteganographyOp(secret);
BufferedImage stego = encodeOp.filter(cover, null);
MappingKey key = encodeOp.getMappingKey();

// Decode from unmodified (or scaled/rotated) cover image and MappingKey
EntropySteganographyOp decodeOp = new EntropySteganographyOp(key);
decodeOp.filter(stego, null);
byte[] recovered = decodeOp.getSecret();
```

## References

 * Shuang-Kui Xia and Jianbin Wu, "[A Modification-Free Steganography Method Based on Image Information Entropy](https://onlinelibrary.wiley.com/doi/10.1155/2018/6256872)", *Security and Communication Networks*, vol. 2018, Article ID 6256872, 2018.

## TODO
