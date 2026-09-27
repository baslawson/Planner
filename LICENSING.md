# Planner licensing

Copyright (C) 2026 baslawson and Planner contributors.

Planner's original source code, documentation and artwork are licensed under the
GNU General Public License, version 3 only (GPL-3.0-only), with the additional
permission below. The complete licence text is in [LICENSE](LICENSE).

Planner is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for details.

## Additional permission under GNU GPL version 3 section 7

If you modify this Program, or any covered work, by linking or combining it with
Google ML Kit's on-device text-recognition SDK and the Google runtime libraries
and bundled recognition models required for that SDK's OCR functionality, the
copyright holders of Planner grant you additional permission to convey the
resulting work.

This permission is limited to the ML Kit OCR integration used by Planner. It does
not extend to unrelated proprietary libraries or services. It does not relicense
Google's software or models, waive their applicable terms, or grant rights that
Google has not granted. You must comply with those terms independently.

All other GPLv3 obligations for Planner's covered code remain in effect,
including providing its Corresponding Source when distributing a combined build.
This permission does not require you to provide source for the unmodified Google
components covered by this exception. Modified versions of Planner may retain or
remove this additional permission as provided by GPLv3 section 7.

Dependency entry point: `com.google.mlkit:text-recognition:16.0.1`, together with
its required Google ML Kit and Google Play services runtime dependencies and OCR
models. See [ML Kit's terms](https://developers.google.com/ml-kit/terms).

## Third-party material

Third-party components retain their own copyright notices and licence terms.
The Planner licence does not replace those terms.

- Bundled fonts: licence notices are in
  [app/src/main/assets/font-licenses](app/src/main/assets/font-licenses).
- Gradle wrapper: its existing Apache-2.0 notices remain in the wrapper scripts.
- Android, AndroidX, Kotlin and other libraries: retain their upstream licences.
- Google ML Kit and required Google runtime components: see the exception above
  and the applicable Google terms.

Planner's application source is available for inspection and modification; the
Google OCR component is not open source. The exception does not change that.
