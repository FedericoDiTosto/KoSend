# Contributing to KoSend

Thanks for your interest in KoSend. Bug reports, translations and code changes are all welcome.

## Reporting a bug

Open an issue with the **Bug report** form. The most useful details are:

- your phone model and Android version
- your Kindle model, firmware and KOReader version
- whether the Kindle finds the phone on its own or only with a manual address
- the lines mentioning `calibre` in `koreader/crash.log` on the Kindle

For security problems, please use **Report a vulnerability** in the Security tab instead of a public issue.

## Proposing a change

For anything bigger than a small fix, open an issue first so we can agree on the approach
before you spend time on it.

1. Fork the repository and create a branch from `main`.
2. Make your change. Keep each pull request focused on one thing.
3. Build it and try it on a real phone with a Kindle running KOReader.
4. Open a pull request against `main` and fill in the template.

## Building

You need JDK 17 and the Android SDK (platform 35, build-tools 35).
Put the SDK path in `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

Then:

```sh
./build.sh debug       # debug APK in dist/
./gradlew screenshots  # regenerate the README screenshots
```

Release builds are signed by the maintainer; you don't need a signing key to contribute.

## Code style

- Kotlin, following the [official style](https://kotlinlang.org/docs/coding-conventions.html).
- Match the code around your change: naming, structure and comment density.
- All user-facing text goes in `app/src/main/res/values/strings.xml`, never hard-coded.
- If your change affects the UI, run `./gradlew screenshots` and include the updated images.

## Translations

Strings live in `app/src/main/res/values/strings.xml` (English, the default).
To add a language, copy it to `values-<code>/strings.xml` (for example `values-de` for German),
translate the values, keep the `name` attributes and placeholders such as `%1$s` unchanged,
and add the language to `AppLanguage.kt`.

For KOReader menu names, use KOReader's own translation for your language so the
instructions match what users see on the Kindle.

## License

KoSend is released under the [GPL-3.0](LICENSE). By contributing, you agree that your
contributions are licensed under the same terms.
