# Restricted client access implementation plan

## 1. Policy core

- [x] Add strict IPv4 and CIDR parsing with canonical storage.
- [x] Add immutable open/restricted policy evaluation.
- [x] Add a shared service-aware gate with rate-limited rejection reporting.
- [x] Add exhaustive plain-JVM policy tests.

## 2. Persistent Android state and UI

- [x] Persist mode and canonical rules in app-private SharedPreferences.
- [x] Expose the current settings through StateFlow for Compose and live server decisions.
- [x] Add a Settings dialog with a toggle, one-rule-per-line editor, validation, and safety copy.
- [x] Keep open mode as the first-run default and make Save atomic.

## 3. Network enforcement

- [x] Gate Tier 1 IPP before USB channel leasing or request forwarding.
- [x] Gate Tier 2 IPP before IPP parsing or spool creation.
- [x] Gate raw port 9100 before transport acquisition and while streaming.
- [x] Gate eSCL before scan parsing, creation, or output delivery.
- [x] Return HTTP 403 for blocked IPP/eSCL clients and close blocked raw clients.
- [x] Record bounded, rate-limited rejection entries in Recent Activity.

## 4. Verification

- [x] Add allowed/blocked real-socket tests for all four entry points.
- [x] Run the complete JVM suite and assemble the debug APK.
- [x] Install on the connected Android device without losing the USB grant.
- [x] Verify blocked and allowed Mac IPP access and rejection visibility.
- [x] Verify eSCL and raw-port blocking, then restore open mode.
