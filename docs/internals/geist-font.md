# Geist font — vendored TTFs

`app/src/main/res/font/geist_regular.ttf` and `geist_semibold.ttf` are the OFL-licensed Geist
Regular and SemiBold weights, fetched once from the `vercel/geist-font` repository
(`fonts/Geist/ttf/Geist-Regular.ttf`, `fonts/Geist/ttf/Geist-SemiBold.ttf`) and committed so the
app keeps building offline, the same reasoning as the vendored Onyx SDK
(`docs/internals/onyx-sdk.md`). It is the typeface the marketing site (`site/`) already loads from
Google Fonts, now bundled for the in-app `EinkTypography` (`app/src/main/java/com/nomadnotes/app/ui/Eink.kt`).

The full SIL Open Font License 1.1 text is kept at `third_party/geist-font/OFL.txt`. Only the
Regular and SemiBold static weights are vendored; re-fetch from the same repo path if another
weight is needed later.
