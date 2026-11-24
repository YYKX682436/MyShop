# Third-party notices

MyShop Phase 1 reuses the application structure and UI components of
[RivenLau/orion-key](https://github.com/RivenLau/orion-key), licensed under the
MIT license. The original Orion Key copyright and license text are retained in
`LICENSE`.

The behavior and product-flow requirements of
[lizhipay/acg-faka](https://github.com/lizhipay/acg-faka) were studied as a
reference. MyShop does not copy its PHP runtime or its frontend.

MyShop uses the official Java binding of
[lionsoul2014/ip2region](https://github.com/lionsoul2014/ip2region) for
replaceable local IPv4/IPv6 XDB lookups. The binding is distributed under the
Apache License 2.0; its dependency metadata and license are available in the
[official Java binding pom](https://github.com/lionsoul2014/ip2region/blob/master/binding/java/pom.xml)
and the repository's [Apache-2.0 license](https://github.com/lionsoul2014/ip2region/blob/master/LICENSE).
The XDB files are provisioned on the production host and are not committed to
this repository.

MyShop uses [Thumbnailator](https://github.com/coobird/thumbnailator) (MIT)
for image resizing and [metadata-extractor](https://github.com/drewnoakes/metadata-extractor)
(Apache-2.0) to read image orientation. Metadata-extractor depends on
[Adobe XMP Core](https://github.com/adobe/XMP-Toolkit-SDK) (BSD-3-Clause).

MyShop includes the renderer dependency closure from
[Liquid Glass WebGL](https://github.com/martin65536/liquid-glass-webgl) at
`b4e0a6910443cea7cdd4b1bfcf7b2394e0a15e48`, copyright 2025 Liquid Glass
WebGL Port Contributors, under Apache-2.0. Its complete license and details of
MyShop's changes are retained in `apps/web/lib/vendor/liquid-glass/`.

The AVIF and HEIC format-probe fixtures are based on “Fox Parade” by Kaede
Fujisaki under CC BY-SA 4.0. Source, attribution, and the HEIC adaptation are
documented in `apps/api/src/test/resources/media-formats/README.md`.

The compact Display P3 and Rec. 709 ICC profiles used for HEIF colour
conversion come from [Compact ICC Profiles](https://github.com/saucecontrol/Compact-ICC-Profiles)
and are dedicated to the public domain under CC0 1.0.

MyShop uses [SnapDOM](https://github.com/zumerlab/snapdom) `3.8.2` for bounded
page capture, copyright 2025 ZumerLab, under the MIT license. The complete
license is retained in `apps/web/lib/vendor/snapdom.LICENSE`.

MyShop uses [commonmark-java](https://github.com/commonmark/commonmark-java)
`0.32.0` and its GFM extensions under BSD-2-Clause. The license is included
in the dependency JARs as `META-INF/LICENSE.txt`. Its autolink extension uses
[autolink-java](https://github.com/robinst/autolink-java) under the MIT license.

MyShop uses [remark-gfm](https://github.com/remarkjs/remark-gfm) `4.0.1` and
[remark-breaks](https://github.com/remarkjs/remark-breaks) `4.0.0` under the
MIT license. It also uses [Vditor](https://github.com/Vanessa219/vditor)
`4.0.1`, [tus-js-client](https://github.com/tus/tus-js-client) `4.3.1`, and
[tus-java-server](https://github.com/tomdesair/tus-java-server) `1.0.0-3.3`
under the MIT license:

Copyright (c) Titus Wormer &lt;tituswormer@gmail.com&gt; (remark-gfm)

Copyright (c) 2017 Titus Wormer &lt;tituswormer@gmail.com&gt; (remark-breaks)

Copyright (c) 2019-present B3log 开源, b3log.org (Vditor)

Copyright (c) 2015 tus - Resumable File Uploads (tus-js-client)

Copyright (c) 2017 Tom Desair (tus-java-server)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
