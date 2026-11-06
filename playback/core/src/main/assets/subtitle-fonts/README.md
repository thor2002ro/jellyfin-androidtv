# Bundled subtitle fonts

These fonts provide a stable primary face for native libass and normal Media3
subtitles. The generated fontconfig file keeps Android system font directories
as fallbacks for scripts that the bundled files do not cover. Android subtitle
views also retain their system fallback for missing glyphs.

The shared libass provider opts in through `LIBASS_BUNDLED_FONT_DIR` to select
matching embedded ASS fonts first, bundled sans-serif families second, and
device fonts last. Each tier checks glyph coverage and keeps weight/style
matching. The directory filter prevents a device's own Noto installation from
winning the bundled tier. Without the environment setting, libass retains its
upstream selection behavior. Normal Media3 subtitles and the settings preview
use the same bundled Noto Sans assets with Android's device fallback.

Sources:

- Noto Sans v2.015: https://github.com/notofonts/latin-greek-cyrillic/releases/tag/NotoSans-v2.015
- Noto Sans Symbols v2.003: https://github.com/notofonts/symbols/releases/tag/NotoSansSymbols-v2.003
- Noto Sans Symbols 2 v2.008: https://github.com/notofonts/symbols/releases/tag/NotoSansSymbols2-v2.008

The selected hinted static TTF files have these SHA-256 hashes:

- `NotoSans-Regular.ttf`: `478C558EA716033CD60C03438F628DFA75694DCF6B5F6D505A2F05FD2B4F3823`
- `NotoSans-SemiBold.ttf`: `A4E91FD530AC2B4EF5367240144FF37D7D65D66CF76F2E9A2187B93C676F92D0`
- `NotoSansSymbols-Regular.ttf`: `D0E98E9A2C046594C5021437273943BE7E79E0FD980FDE125279E22302212595`
- `NotoSansSymbols2-Regular.ttf`: `C4A0A80F0041CE4BE81E2478FAAD22776D23EDB98AE3F0D19BD37044820ECF9D`

All files are distributed under the SIL Open Font License 1.1 in `OFL.txt`.
