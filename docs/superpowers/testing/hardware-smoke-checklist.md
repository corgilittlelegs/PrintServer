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

## Tier-2: host-based printer, on-device rendering (HP DeskJet 2338)

Prereq: 2338 connected via OTG, server running, NO legacy banner shown
(this family is fully supported now).

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
- [ ] Multi-page PDF (3+ pages) prints all pages in order.
- [ ] Submit two jobs back-to-back from different machines: both print, in order.
- [ ] Corrupt PDF (truncate a real one) → job aborts, printer does not hang,
      NEXT job still prints fine.
- [ ] Cancel a queued (not yet printing) job from the client — it never prints;
      canceling an already-processing job gets a clear rejection, not a hang.
- [ ] Raw 9100 path still works from a PC with the HP driver installed.
