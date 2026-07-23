# Ink supply status plan

- [x] Add supply status data model and tolerant LEDM parser/query helper.
- [x] Add JVM tests for request shape, endpoint fallback, and common HP XML fields.
- [x] Thread supply status through `ServerStatus` during Tier 2 startup.
- [x] Add a Supplies card in the Android UI.
- [x] Map parsed supplies to IPP `marker-*` attributes for macOS/CUPS clients.
- [ ] Run unit tests, install on tablet, and inspect live logs/UI/macOS.

Unit tests pass and the build is installed on the tablet. Live log/UI inspection is still
waiting for the server to be started again after reinstall.
