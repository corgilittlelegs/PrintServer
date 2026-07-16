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
