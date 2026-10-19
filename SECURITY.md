# Security policy

Sceau reads identity documents and verifies their authenticity. A flaw in it can
expose personal data or make a forged document pass as genuine, so security
reports are handled before anything else.

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Report it privately
through GitHub's vulnerability reporting form:

**https://github.com/mgdx/Sceau/security/advisories/new**

The form is private: only the maintainer sees the report until a fix is
published. If you cannot use it, open a public issue that only says you have a
security concern, without any detail, and a private channel will be arranged.

What is useful in a report:

- the version of Sceau (visible on the About screen) and the Android version;
- the affected area: NFC reading, Passive, Chip or Active Authentication, the
  trust store, Master List import, the JPEG 2000 decoder, the interface;
- how to reproduce it, ideally with a simulated chip from the `:testchip`
  module, a crafted Master List or a crafted JPEG 2000 file;
- what an attacker gains.

**Never send a photo of a real document, its MRZ, its CAN, nor any data read
from a real chip.** A test document, or a simulated one, is enough.

## What counts

Anything that breaks one of the guarantees the project makes:

- a forged or altered document, or an unknown issuer, that gets an **Authentic**
  or **Valid signature** verdict (chain validation, SOD signature, DG hashes,
  Chip or Active Authentication, PACE-CAM, Master List signature);
- a Master List or certificate import that lets a certificate into the store
  without the checks described in `docs/trust-store.md`;
- data read from a document, or an access key (CAN, MRZ), that ends up on disk,
  in a log, in a cache, in a screenshot, in an exception message or in another
  application;
- memory corruption in the native JPEG 2000 decoder (OpenJPEG over JNI), even
  though it runs in an isolated process;
- a crash or hang caused by a crafted chip, Master List or image.

A document that Sceau fails to read, or reads with a wrong verdict because its
issuer deviates from ICAO 9303, is not a vulnerability: it is a bug, to be
reported as a public issue following `README.md`, without any personal data.

## What to expect

- Acknowledgement within 7 days.
- A fix, or a decision not to fix with its reasons, within 90 days for a
  confirmed vulnerability, faster for anything that puts personal data at risk.
- The fix is published in a new release on GitHub and F-Droid, with a GitHub
  security advisory crediting you unless you prefer to stay anonymous.

Only the latest release is supported. There is no bug bounty.

## Scope

In scope: the code in this repository, the release APKs published on GitHub and
the build published by F-Droid, and the built-in trust store.

Out of scope: vulnerabilities in Android itself, in the NFC stack of a given
phone, or in dependencies that do not affect Sceau. Upstream flaws in OpenJPEG
are still welcome if Sceau's bundled copy is affected, so that it can be updated.

## Good faith

Testing is welcome on your own documents and devices. Do not test on documents
that are not yours, and do not tamper with a document: that may be illegal
regardless of this policy.
