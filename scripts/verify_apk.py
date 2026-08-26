#!/usr/bin/env python3
"""Check the packaging properties the app silently depends on at runtime.

Each of these has already broken the app once or would break it on a device
class we cannot test here, and none of them shows up as a build error:

  * the engine libraries are actually in the APK;
  * ggml's CPU-feature variants are all there, or runtime dispatch has nothing
    to choose from;
  * android:extractNativeLibs is true -- ggml finds those variants by listing
    the app's native library directory, and with the modern packaging the
    files stay inside the APK and that directory is empty, so no backend
    registers and whisper aborts on a null CPU device;
  * every library is laid out for 16 KB pages, which recent arm64 devices
    require and older ones tolerate.

Usage: verify_apk.py <apk>
"""

import struct
import sys
import zipfile

ABI = "arm64-v8a"
REQUIRED = [
    "libsummarizer.so",
    "libggml-base.so",
    "libllama.so",
    "libwhisper.so",
    "libc++_shared.so",
]
CPU_VARIANT_PREFIX = "libggml-cpu-"
MIN_CPU_VARIANTS = 2
PAGE_SIZE = 16384


def parse_axml_strings(data):
    """Return (strings, chunks) for a compiled binary AndroidManifest.xml."""
    strings, resmap, elements = [], [], []
    pos = 8
    while pos + 8 <= len(data):
        ctype, hsize, csize = struct.unpack_from("<HHI", data, pos)
        if csize == 0:
            break
        if ctype == 0x0001:  # string pool
            count, _styles, flags, str_start, _ = struct.unpack_from("<IIIII", data, pos + 8)
            utf8 = bool(flags & (1 << 8))
            offsets = struct.unpack_from("<%dI" % count, data, pos + 28)
            base = pos + str_start
            for off in offsets:
                p = base + off
                if utf8:
                    n = data[p]
                    p += 1
                    if n & 0x80:
                        p += 1
                    n = data[p]
                    p += 1
                    if n & 0x80:
                        n = ((n & 0x7F) << 8) | data[p]
                        p += 1
                    strings.append(data[p:p + n].decode("utf-8", "replace"))
                else:
                    n = struct.unpack_from("<H", data, p)[0]
                    p += 2
                    if n & 0x8000:
                        n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                        p += 2
                    strings.append(data[p:p + n * 2].decode("utf-16-le", "replace"))
        elif ctype == 0x0180:  # resource id map
            resmap = list(struct.unpack_from("<%dI" % ((csize - hsize) // 4), data, pos + hsize))
        elif ctype == 0x0102:  # start element
            _ns, name = struct.unpack_from("<iI", data, pos + hsize)
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, pos + hsize + 8)
            attrs = {}
            ap = pos + hsize + attr_start
            for i in range(attr_count):
                _a_ns, a_name, a_raw, typed, a_data = struct.unpack_from("<iiiIi", data, ap + i * attr_size)
                a_type = (typed >> 24) & 0xFF
                key = strings[a_name] if strings[a_name] else "res:0x%08x" % (
                    resmap[a_name] if a_name < len(resmap) else 0)
                if a_type == 0x12:
                    value = bool(a_data)
                elif a_type == 0x03:
                    value = strings[a_raw if a_raw >= 0 else a_data]
                else:
                    value = a_data
                attrs[key] = value
            elements.append((strings[name], attrs))
        pos += csize
    return elements, resmap


def load_alignment(blob):
    """Smallest p_align across the ELF's PT_LOAD segments."""
    if blob[:4] != b"\x7fELF" or blob[4] != 2:
        raise ValueError("not a 64-bit ELF")
    phoff = struct.unpack_from("<Q", blob, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", blob, 0x36)
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type = struct.unpack_from("<I", blob, base)[0]
        if p_type == 1:  # PT_LOAD
            aligns.append(struct.unpack_from("<Q", blob, base + 48)[0])
    if not aligns:
        raise ValueError("no PT_LOAD segments")
    return min(aligns)


def main(path):
    failures = []
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        libs = sorted(n for n in names if n.startswith("lib/%s/" % ABI) and n.endswith(".so"))
        print("Native libraries in %s:" % path)
        for name in libs:
            print("  %s" % name.split("/")[-1])

        present = {n.split("/")[-1] for n in libs}
        for required in REQUIRED:
            if required not in present:
                failures.append("missing %s" % required)

        variants = sorted(n for n in present if n.startswith(CPU_VARIANT_PREFIX))
        print("ggml CPU variants packaged: %d" % len(variants))
        if len(variants) < MIN_CPU_VARIANTS:
            failures.append("runtime CPU dispatch has %d variant(s), need %d"
                            % (len(variants), MIN_CPU_VARIANTS))

        elements, _ = parse_axml_strings(apk.read("AndroidManifest.xml"))
        extract = None
        for tag, attrs in elements:
            if tag == "application" and "extractNativeLibs" in attrs:
                extract = attrs["extractNativeLibs"]
        print("android:extractNativeLibs = %s" % extract)
        if extract is not True:
            failures.append(
                "extractNativeLibs is %s; ggml scans the native library "
                "directory for its CPU backends and that directory is only "
                "populated when the libraries are extracted on install" % extract)

        misaligned = 0
        for name in libs:
            try:
                align = load_alignment(apk.read(name))
            except ValueError as exc:
                failures.append("%s: %s" % (name, exc))
                continue
            if align < PAGE_SIZE:
                misaligned += 1
                failures.append("%s: PT_LOAD alignment 0x%x, need 0x%x for 16 KB pages"
                                % (name.split("/")[-1], align, PAGE_SIZE))
        print("ELF load alignment: %d of %d libraries at >= %d bytes"
              % (len(libs) - misaligned, len(libs), PAGE_SIZE))

    for failure in failures:
        print("::error::%s" % failure)
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
