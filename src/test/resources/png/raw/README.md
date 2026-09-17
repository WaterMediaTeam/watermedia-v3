# PNG reference provenance

`broken.pam` is the first image decoded from `../broken.png` by the JDK's `javax.imageio.ImageIO` PNG reader, stored as 8-bit RGBA PAM. The fixture decodes successfully despite its filename. WaterMedia classes were not used to produce the reference.

Generated on September 7, 2026 with Microsoft OpenJDK 25.0.4.1+1-LTS. Dimensions: 1439 × 1440. From the project root, reproduce it with:

```powershell
java src/test/tools/GeneratePngReference.java src/test/resources/png/broken.png src/test/resources/png/raw/broken.pam
```

| File | SHA-256 |
| --- | --- |
| `broken.png` | `1c10305592e15d1df2061f1bd6d86fd9359ef30fc49964cd852ef4850a45344c` |
| `broken.pam` | `1a99940314a519e3a6412555bf711ff8f208a5e46429328e9c8f6b2abd652852` |

The other PAM files predate this work; their original generator and version were not recorded.
