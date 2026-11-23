# Liquid Glass WebGL source

Source: <https://github.com/martin65536/liquid-glass-webgl> at commit
`b4e0a6910443cea7cdd4b1bfcf7b2394e0a15e48`. The renderer dependency closure
is copied from that commit; no demo pages or other releases are included.
Copyright 2025 Liquid Glass WebGL Port Contributors. The complete Apache-2.0
license is in `LICENSE`.

MyShop modifications, identified at the start of modified source files:

- Add a canvas-sized transparent output framebuffer; retain the original scene
  framebuffer for backdrop and tab-content sampling. The per-element path
  composites each calculated glass body into both targets and preserves alpha
  correctly for straight-alpha and additive passes.
- Accept page-aligned Canvas backdrop frames, update same-sized textures in
  place, and invalidate cached contents while retaining the reusable pool.
- Pause rendering in background tabs and use deterministic targets for reduced
  motion. Correct upstream declaration mismatches and strict TypeScript errors.
- Remove the final blank line in two shader source files for repository
  whitespace checks; their shader code is unchanged.

MyShop's geometry, interaction adapter and bounded DOM capture live outside
this directory in `components/store/liquid-tabs/`.
