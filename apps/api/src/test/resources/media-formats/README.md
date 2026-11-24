# Format probe fixtures

`fox.avif` is “Fox Parade” by Kaede Fujisaki, distributed by the
[AVIF sample images project](https://github.com/link-u/avif-sample-images#fox-parade---odd-dimensions-images)
under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Source:
[fox.profile0.8bpc.yuv420.avif](https://github.com/link-u/avif-sample-images/blob/master/fox.profile0.8bpc.yuv420.avif).

`fox-derived.heic` is a modified version of that image: it was resized to 800 px
and converted to HEIC using macOS `sips`. This adaptation is also licensed
under CC BY-SA 4.0.

`fox-p3-nclx.heic` is another adaptation: the source was converted to Display
P3, encoded as HEIC, and tagged with nclx primaries 12 and transfer function
13. `fox-rec709-nclx.heic` is similarly converted and tagged as Rec. 709.
`fox-p3-icc.heic` is a smaller adaptation carrying an embedded Display P3
ICC profile. `fox-conflict.heic` and `fox-hdr.heic` are adaptations with,
respectively, conflicting ICC/nclx declarations and an HDR nclx declaration;
they exercise explicit rejection rather than silently incorrect color. These
adaptations are also licensed under CC BY-SA 4.0. All files are used only as
image-decoding and color-safety test fixtures.
