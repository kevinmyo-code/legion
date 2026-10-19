# Books engine check: Readium Kotlin toolkit (2026-10-19)

Verdict: STOPPED before building. Readium 3.4.0 (current) does not build in this project, and the
older line that does carries a Media3 ExoPlayer pull that CLAUDE.md section 3 lists as dropped.
Ticket 02 waits on a ruling. Nothing was built on another engine.

## Facts, each from Maven Central or the 3.4.0 README (not a Gradle probe)

| Question | Answer |
|---|---|
| Current stable | 3.4.0, released 2026-09-11 |
| 3.4.0 toolchain | Kotlin 2.4.20, compileSdk 37, minSdk 24, Gradle 9.7.0 |
| This app | Kotlin plugin 2.2.10 (root build.gradle.kts; the libs.versions.toml ref of 2.1.0 is stale), compileSdk 36, minSdk 24, AGP 9.2.1 |
| 3.4.0 fits? | No. Kotlin 2.2.10 reads metadata up to 2.3; 2.4 metadata fails. compileSdk 37 also needed |
| 3.2.0 / 3.3.0 | Kotlin 2.3.20 (metadata readable by 2.2.10), compileSdk 36, minSdk 23 (3.2.0). Plausible fit, NOT compiled here |
| 3.1.2 | Kotlin 2.1.21, compileSdk 36, minSdk 23. Plausible fit, NOT compiled here |
| Media3 | readium-navigator POM depends on media3-session, media3-common-ktx and media3-exoplayer (1.10.0 in 3.2/3.3, 1.11.0 in 3.4.0). App pins media3-session 1.4.1 and deliberately has NO ExoPlayer (CLAUDE.md section 3, libs.versions.toml comment). Taking Readium means ExoPlayer plus a media3 bump for the Android Auto probe |
| EPUB navigator | View/Fragment based (EpubNavigatorFragment, needs a FragmentActivity host). MainActivity is a ComponentActivity. Also pulls appcompat, webkit, constraintlayout, recyclerview, jsoup |
| PDF adapter | readium-adapter-pdfium (document + navigator) over barteksc PdfiumAndroid and AndroidPdfViewer 3.2.8. Not PdfBox. Old fork; 16 KB page-size support on newer devices unverified. Alternative: skip Readium PDF and use android.graphics.pdf.PdfRenderer |
| CBZ | README marks image/CBZ as in progress |
| CBR (RAR) | README marks it "?" (unsupported). Needs a separate reader. junrar 8.1.2 exists; its licence is the UnRAR freeware licence (forbids re-creating the RAR compressor), not an OSI permissive licence. Needs Kevin's ruling, else CBR is stated unsupported in words |
| Read-aloud | readium-navigator-media-tts, EPUB only. README: TTS for PDF not supported. Matches the ticket (PDF read-aloud only with a text layer would be our own code) |
| APK size | AARs for 3.4.0: navigator 1.15 MB, shared 1.55 MB, streamer 0.30 MB, media-tts 0.25 MB, pdfium-document 0.02 MB. Plus ExoPlayer, AndroidPdfViewer and its native pdfium .so, which were not measured. Real APK delta not measured (no build run) |

## Decision owed

1. Readium 3.2.0 or 3.3.0 (needs the Media3 ExoPlayer exception, a FragmentActivity host for the
   reader, and a spike to confirm it compiles on Kotlin 2.2.10), or
2. Own reader: EPUB through a WebView/own parser, PdfRenderer for PDF, ZipInputStream/junrar for
   comics. More code, no ExoPlayer, no toolkit lag.
3. Separately: CBR via junrar (licence above) or unsupported in words.
