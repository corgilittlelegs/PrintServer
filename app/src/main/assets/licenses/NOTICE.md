# Third-party components

This app bundles native code compiled from the following third-party
projects. Their full license texts are alongside this file.

- **Ghostscript 10.07.1** — AGPL-3.0. Source: https://www.ghostscript.com/
  (upstream archives: https://github.com/ArtifexSoftware/ghostpdl-downloads)
- **HPLIP 3.24.4 (hpcups filter)** — GPL-2.0. Source: https://developers.hp.com/hp-linux-imaging-and-printing
- **CUPS 2.4.19 (raster I/O)** — Apache-2.0. Source: https://github.com/OpenPrinting/cups
- **HP JIPP Core 0.7.18 (IPP packet parsing)** — Apache-2.0. Source: https://github.com/HP/jipp


Build scripts and any source patches applied to the above are in this
repository's `native/` directory (`native/build-ghostscript.sh`,
`native/fetch-hpcups-sources.sh`, `native/patches/`), which together with
the pinned upstream version numbers satisfy the AGPL/GPL requirement that
corresponding source be available to anyone who receives the binary.

**Distribution note:** this build is currently for personal/sideload use
only. Before any wider distribution (Play Store or otherwise), AGPL
specifically requires that users interacting with the app over a network
be able to obtain the exact corresponding source — confirm the above
satisfies that for whatever distribution channel is used, and update the
"Licenses" screen's source link if the repository moves.
