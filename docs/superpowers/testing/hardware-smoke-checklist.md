# Hardware Smoke Test Checklist

Prereqs: Android phone (API 26+, USB host support), USB OTG adapter, a
post-2013 USB printer, phone and test clients on the same Wi-Fi network.
Install: `./gradlew :app:installDebug`.

## Setup
- [ ] Plug printer into phone via OTG. App launches (attach intent) or open it manually.
- [ ] Tap Start server. Grant USB permission if prompted.
- [ ] UI shows printer model + `http://<phone-ip>:8631`.
- [ ] Tap "Disable battery optimization" and accept.

## Discovery
- [ ] macOS: `dns-sd -B _ipp._tcp` lists the printer within ~5 s.
- [ ] macOS: `dns-sd -L "<name>" _ipp._tcp` shows TXT: rp=ipp/print, pdl=..., UUID=...

## Print paths (one page each; verify paper output)
- [ ] macOS: System Settings → Printers → printer appears via Bonjour → print a PDF page.
- [ ] Windows 11: Settings → Bluetooth & devices → Printers → Add device → appears → print test page.
- [ ] iPhone: Share → Print from Safari → printer appears (requires URF in TXT) → print.
- [ ] Linux: `ipptool -tv ipp://<phone-ip>:8631/ipp/print get-printer-attributes.test` passes,
      then `lp -h <phone-ip>:8631 -d ipp/print <file.pdf>` OR add via CUPS "everywhere" driver.
- [ ] Host phone itself: open a PDF → Print → Default Print Service lists the printer → print.

## Resilience
- [ ] Print two jobs from two machines at the same time — both complete.
- [ ] Unplug USB mid-idle: UI shows "Printer disconnected", mDNS entry disappears.
- [ ] Replug: app auto-starts, printer rediscoverable, printing works.
- [ ] Toggle Wi-Fi off/on on the phone: after reconnect, restart server, clients rediscover.
- [ ] Screen off 10 minutes, then print from a laptop — job still goes through.
- [ ] Cancel a job from the client mid-transfer; next job still prints (channel hygiene).

## Legacy path (only if a non-IPP-USB printer is available)
- [ ] UI shows the "no driverless support" banner.
- [ ] From a PC with the vendor driver installed, add a raw TCP/IP printer at
      `<phone-ip>:9100` and print a page.

## Printer info card (both tiers)

- [ ] Connect a Tier 1 (IPP-USB) printer, start the server. Manufacturer,
      model, serial, VID:PID, PDL list, "Tier 1", and connect time all show.
- [ ] Connect a Tier 2 (host-based) printer, start the server. Same fields
      show, with "Tier 2" and PDLs reflecting that printer's PCL/PJL support.
- [ ] Manufacturer/model shown match the printer's actual make/model, not a
      generic placeholder.
- [ ] Stop the server. All printer-info rows disappear (no stale data left
      showing after the printer is no longer being served).
- [ ] If reachable, test a printer that doesn't respond to GET_DEVICE_ID (or
      temporarily stub `readDeviceId` to return null): manufacturer/model/PDL
      rows are hidden, not shown as blank or "Unknown" — app doesn't crash,
      serial/VID:PID/tier/connect time still show normally.

## Tier-2: host-based printer, on-device rendering (HP DeskJet 2300 series)

Prereq: a DeskJet 2300-series unit connected via OTG, server running, NO legacy
banner shown (this family is verified against `VerifiedPrinterProfiles.DESKJET_2300`
and is fully supported).

Capture logs for the whole session below — start this before the first print
and leave it running:
```
adb logcat -s ServerService JobQueue hpcupsjni gsjni
```

Run the `ipptool`/`lp` probes first — they catch missing/wrong IPP attributes
in minutes, before burning paper on a print that was never going to render
correctly:

- [ ] `ipptool -tv ipp://<phone-ip>:8631/ipp/print get-printer-attributes.test` passes
      with no missing/unexpected-attribute warnings (ipptool ships with CUPS).
- [ ] `ipptool -tv ipp://<phone-ip>:8631/ipp/print print-job.test` (or a
      hand-rolled `.test` file posting a real PDF) reports job-id and
      job-state as expected.
- [ ] `lp -h <phone-ip>:8631 -d ipp/print page.pdf` from a Linux/macOS shell
      completes without CUPS falling back to a generic/raw queue.

Then the physical print checks:

- [ ] macOS discovers the printer via Bonjour and prints one text PDF page —
      output physically correct (no garbage, no offset, right colors).
- [ ] Windows 11 adds it driverlessly and prints a page.
- [ ] iPhone AirPrint prints a photo (color fidelity check).
- [ ] Multi-page PDF (3+ pages) prints all pages in order. (Covers the
      streaming-spool and native multi-page-fixture hardening — no separate
      check needed; if pages come out in order and none are dropped/duplicated,
      this item is sufficient as-is.)
- [ ] Submit two jobs back-to-back from different machines: both print, in order.
- [ ] Corrupt PDF (truncate a real one) → job aborts, printer does not hang,
      NEXT job still prints fine.
- [ ] Cancel a queued (not yet printing) job from the client — it never prints;
      canceling an already-processing job gets a clear rejection, not a hang.
- [ ] Raw 9100 path still works from a PC with the HP driver installed.

### Document format coverage (`application/pdf`, `image/pwg-raster`, `image/jpeg`)

- [ ] `application/pdf`: print a text+image PDF page from any client above —
      legible text, correct image placement/colors.
- [ ] `image/jpeg`: send a JPEG directly, e.g.
      `ipptool -tv -d document-format=image/jpeg -f photo.jpg ipp://<phone-ip>:8631/ipp/print print-job.test` —
      photo prints with correct orientation and no color corruption.
- [ ] `image/pwg-raster`: most consumer clients never emit this format directly,
      so construct one — e.g. `gs -sDEVICE=pwgraster -o out.pwg page.pdf` (or
      any PWG-Raster encoder) — and submit it the same way as the JPEG case
      above (`document-format=image/pwg-raster`); output is legible and
      correctly oriented, confirming the app doesn't only work when Ghostscript
      itself produced the raster.

### Quality levels (Draft / Normal / High)

- [ ] Print the same text+graphics page at Draft, Normal, and High quality
      (three separate jobs, same source file). Draft is visibly coarser/faster
      than Normal; Normal vs. High may look similar on plain text but neither
      is silently substituting Draft's output — all three legible and correct.
      Per `VerifiedPrinterProfiles`, Draft renders at 300dpi and Normal/High at
      600dpi, so Draft should look noticeably less sharp under a loupe or close
      inspection even where it isn't obvious at arm's length.

### Color modes

- [ ] Print a page with clear color content (e.g. a color photo or colored
      chart) once in Color and once in Monochrome. Color output matches source
      colors. Monochrome output is genuinely grayscale/black — no residual
      color tint or unconverted color plane bleeding through.

### Mixed-client contention (Task 12 hardening: shared write lock, 5 s timeout)

- [ ] Start an IPP print job on a large/high-quality multi-page document (big
      enough that USB transfer is visibly still in progress, e.g. High quality,
      3+ pages) from one machine. While it's actively writing, from a second
      machine attempt `nc <phone-ip> 9100 </dev/null` (or add/print to the raw
      9100 queue) — the raw connection should be cleanly rejected (socket
      closes, no hang) within ~5 s, not accepted and left dangling. The
      in-progress IPP job must still complete correctly (correct page count,
      no corruption/garbage on the printed output). Confirm the rejection in
      the log capture above: `ServerService`/`JobQueue` logs a message like
      "raw 9100 client connection rejected: legacy printer transport busy".
- [ ] Once the IPP job finishes, retry the same raw-9100 connection — it now
      succeeds (lock isn't stuck held after the print completes).

## eSCL scan server (Spec B)

Prereq: DeskJet 2300-series MFP connected via OTG, server running, a physical page on
the flatbed before each scan check below.

- [ ] macOS: `dns-sd -B _uscan._tcp` lists the scanner within ~5 s.
- [ ] macOS: Image Capture (or Preview's Import from Scanner) discovers the scanner and
      shows its capabilities (resolution/color options) without a driver install.
- [ ] Scan one page at the default settings — output is a valid, correctly-oriented JPEG.
- [ ] Scan at 600dpi if offered — output is visibly higher-resolution than the 300dpi scan.
- [ ] Scan in grayscale — output is genuinely grayscale, not color.
- [ ] Start a scan from one client, then attempt a second scan from another client before
      the first completes — second attempt is rejected/queued sanely, doesn't crash or
      hang the app.
- [ ] Stop and restart the server — scanner disappears from `dns-sd -B _uscan._tcp` and
      reappears within ~5 s of restart.
