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

MyShop uses [SnapDOM](https://github.com/zumerlab/snapdom) `3.8.2` for bounded
page capture, copyright 2025 ZumerLab, under the MIT license. The complete
license is retained in `apps/web/lib/vendor/snapdom.LICENSE`.
