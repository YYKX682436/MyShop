#!/bin/sh
# Run inside the API runtime image with real HEIC and AVIF files mounted read-only:
#   /bin/sh probe-vips-image-formats.sh /samples/example.heic /samples/example.avif
set -eu

if [ "$#" -ne 2 ] || [ ! -r "$1" ] || [ ! -r "$2" ]; then
  printf '%s\n' 'Usage: probe-vips-image-formats.sh REAL_HEIC REAL_AVIF' >&2
  exit 2
fi

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/myshop-vips-probe.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM

vips --version
ffprobe -version | sed -n '1p'
heif-info --version

profile=
for candidate in /usr/share/color/icc/sRGB.icc /usr/share/color/icc/colord/sRGB.icc /usr/share/color/icc/ghostscript/srgb.icc; do
  if [ -f "$candidate" ]; then profile=$candidate; break; fi
done
if [ -z "$profile" ]; then
  printf '%s\n' 'No installed sRGB ICC profile' >&2
  exit 1
fi

for format in heic avif; do
  if [ "$format" = heic ]; then source_file=$1; else source_file=$2; fi
  if [ "$(dd if="$source_file" bs=1 skip=4 count=4 2>/dev/null)" != ftyp ]; then
    printf '%s is not an ISO image container\n' "$format" >&2
    exit 1
  fi
  if [ "$format" = heic ]; then brands='heic|heix|hevc|hevx'; else brands='avif|avis'; fi
  if ! dd if="$source_file" bs=1 count=128 2>/dev/null | LC_ALL=C grep -aqE "$brands"; then
    printf '%s sample does not carry the expected format brand\n' "$format" >&2
    exit 1
  fi
  heif-info "$source_file" > /dev/null
  vipsthumbnail "$source_file" --size '1600x>' --export-profile "$profile" \
    -o "$work_dir/$format.webp[Q=82,effort=4,strip]"
  test -s "$work_dir/$format.webp"
  vipsheader "$work_dir/$format.webp"
  printf '%s original=%s output=%s bytes\n' "$format" "$(wc -c < "$source_file" | tr -d ' ')" \
    "$(wc -c < "$work_dir/$format.webp" | tr -d ' ')"
done
