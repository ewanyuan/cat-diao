# Languages

The phone UI follows the system language unless **More → Language** chooses one. The choice is stored in the `bridge` preference `app_language` (`system` or a BCP 47 tag) and applied with `createConfigurationContext`, including on Android 10. Android 13 and newer also mirror it with `LocaleManager`, so the launcher name and accessibility label follow the choice. On Android 10–12 those system labels follow the system language; screens, the floating cat, and notifications follow the in-app choice.

Unsupported system languages use English. `zh-HK`, `zh-MO`, and `zh-Hant` use Traditional Chinese. Other Chinese locales use Simplified Chinese. Portuguese uses Brazilian Portuguese only for `pt-BR`; Portugal Portuguese uses English. A region of a supported language uses that language (`es-MX` → Spanish).

## Shipped locales

`zh-CN`, `zh-TW`, `en`, `ja`, `ko`, `es`, `fr`, `de`, `pt-BR`, `ru`, `ar`, `hi`, `id`, `vi`, `th`, `it`, `tr`, `nl`, `pl`, `uk`.

English in `values/` is the fallback. Extra copies, not separate translations: `values-zh`, `values-zh-rSG`, and `values-b+zh+Hans` (Simplified); `values-zh-rHK`, `values-zh-rMO`, and `values-b+zh+Hant` (Traditional); `values-in` (legacy Indonesian, same as `values-id`).

The language list shows each name in its own language. **Follow system** is translated.

Arabic sets layout direction to RTL (`android:supportsRtl="true"`). Rows use start/end padding and margins. The forward chevron is `‹` in Arabic and `›` otherwise. The floating cat’s ✕ stays on the inner physical side, so that window is kept left-to-right.

## What stays untranslated

Protocol fields and machine values stay fixed: `/hello` `name` is `猫叼`, the download folder payload is `下载/猫叼`, the Bluetooth service name is `猫叼小窝`, capture `source` values stored for the computer stay the existing Chinese tokens (the phone translates them only when showing the list), and commands such as `run.ps1 pair` are inserted with `%1$s`. Developer docs are not fully translated.

## Add a locale

1. Add the BCP 47 tag to `LocalePolicy.TAGS` and `NATIVE_NAMES`, and to `res/xml/locales_config.xml`.
2. Add a row in `android/i18n/catalog.py` (`LOCALES` and `QUALIFIER`) and a translation for every string and plural in `catalog.py` / `more_strings.py`.
3. Run `python3 android/i18n/catalog.py`.
4. For a right-to-left language, set `chevron_forward` and rely on `supportsRtl`. Plural quantities must match that language’s Android rules (`other` at minimum; Arabic needs zero/one/two/few/many/other).
5. Run `tests/test_strings.py`. It checks key parity, format tokens, plural quantities, aliases, and that Java UI strings are not leftover Chinese.

Translations in this tree were written for the app and have **not** been reviewed by native speakers.
