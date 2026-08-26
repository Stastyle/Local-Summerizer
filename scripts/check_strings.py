#!/usr/bin/env python3
"""Validate the Android string resources before AGP does.

Three mistakes are easy to make here and all of them fail late, in the
resource compiler, with a message that does not name the cause:

  * an unescaped apostrophe ("the model's") — aapt rejects the whole file;
  * a string added to one locale and not the other, so the UI silently falls
    back to English for that one line;
  * format placeholders that differ between locales, which throws at runtime
    inside getString() rather than at build time.

Usage: check_strings.py <values-dir> [<values-dir> ...]
"""

import re
import sys
import xml.etree.ElementTree as ET

# %1$s, %2$d, and the bare %s / %d forms.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sdf]")
# An apostrophe not preceded by a backslash.
BARE_APOSTROPHE = re.compile(r"(?<!\\)'")


def load(path):
    root = ET.parse(path).getroot()
    return {
        child.get("name"): "".join(child.itertext())
        for child in root
        if child.tag == "string"
    }


def main(paths):
    failures = []
    locales = {}

    for path in paths:
        strings = load(path)
        locales[path] = strings
        for name, text in strings.items():
            if BARE_APOSTROPHE.search(text):
                failures.append(
                    "%s: %s has an unescaped apostrophe; write \\' " % (path, name))

    reference_path, reference = next(iter(locales.items()))
    for path, strings in list(locales.items())[1:]:
        missing = sorted(set(reference) - set(strings))
        extra = sorted(set(strings) - set(reference))
        for name in missing:
            failures.append("%s: missing %s (present in %s)" % (path, name, reference_path))
        for name in extra:
            failures.append("%s: has %s, absent from %s" % (path, name, reference_path))
        for name in sorted(set(reference) & set(strings)):
            want = sorted(PLACEHOLDER.findall(reference[name]))
            got = sorted(PLACEHOLDER.findall(strings[name]))
            if want != got:
                failures.append(
                    "%s: %s has placeholders %s, but %s has %s"
                    % (path, name, got, reference_path, want))

    for failure in failures:
        print("::error::%s" % failure)
    print("%d locale(s), %d strings each, %d problem(s)"
          % (len(locales), len(reference), len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
