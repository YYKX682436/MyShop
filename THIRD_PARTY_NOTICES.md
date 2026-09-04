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
