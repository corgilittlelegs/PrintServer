# Restricted client access design

## Goal

Add an optional compatibility-preserving access mode that prevents unapproved LAN clients from
printing or scanning. Open access remains the default. This phase is a network admission control,
not password authentication: it reduces accidental and opportunistic use on a shared Wi-Fi network
without claiming cryptographic client identity.

## Security boundary

One plain-Kotlin access gate is the source of truth for every LAN entry point:

- Tier 1 IPP-USB relay on port 8631
- Tier 2 local IPP server on port 8631
- raw JetDirect/AppSocket printing on port 9100
- eSCL scanning on port 8632

For HTTP services, the server first consumes only the already bounded HTTP head so it can return a
reliable `403` without a TCP reset caused by closing a socket with unread request bytes. The gate
still runs before any request body is read, IPP is parsed, a USB channel is leased, a spool file is
created, or a scan begins. IPP gates are checked for every request on a persistent connection. Raw
sessions are gated immediately and rechecked while streaming so a rule removal terminates an
existing long-lived client promptly.

Blocked HTTP clients receive `403 Forbidden` with a zero-length body and a closed connection.
Blocked raw clients are closed without reading or forwarding bytes. Rejections are recorded in the
bounded activity feed and rate-limited per client and service so a denied connection flood cannot
displace the entire useful history continuously.

## Rules and persistence

Settings contain an `OPEN` or `RESTRICTED` mode and a set of IPv4 rules. A rule is either one exact
address such as `192.168.0.100` or CIDR such as `192.168.0.0/28`. Hostnames are forbidden: resolving
names inside the security decision would add DNS ambiguity and blocking network work. Parsing uses
unsigned, overflow-safe integer arithmetic and stores canonical network addresses.

Restricted mode with an empty rule set denies every LAN client. The Android device's local UI does
not pass through the network gate, so it remains available to repair the configuration. Settings
are stored in private SharedPreferences and exposed through an in-process StateFlow. A running
server reads the current immutable policy at every decision, so saving settings does not require a
printer-service restart.

The initial implementation deliberately supports IPv4 only because `WifiAddress` binds every
server to an `Inet4Address`. If IPv6 binding is added later, IPv6 rules must be designed and tested
before that surface is exposed.

## User experience

The Settings menu opens a Restricted Access dialog. It explains that this is a device guest list,
not password encryption. The user can enable the mode and enter one rule per line. Save is atomic:
if any line is invalid, no partial configuration is applied. The dialog shows examples and warns
that DHCP can change a device's address; a router reservation or appropriately narrow CIDR rule is
recommended.

Bonjour advertisements remain visible to the LAN. Per-client mDNS hiding is not available through
the current Android NSD API, and suppressing discovery globally would also hide the printer from
approved driverless clients. Unapproved clients may therefore see the service name but receive no
print or scan access.

## Non-goals

- Passwords, HTTP Basic/Digest authentication, TLS, IPPS, or certificate provisioning
- MAC-address identity (not reliably available from accepted Android sockets)
- Hostname rules or automatic trust of the whole Wi-Fi subnet
- Treating an IP allowlist as protection against an attacker capable of address spoofing
- Per-client Bonjour visibility

## Verification

Plain JVM tests cover exact/CIDR parsing, canonicalization, matching, invalid input, open mode,
empty restricted mode, and dynamic policy replacement. Real-socket tests prove every server rejects
an unapproved loopback client before downstream work and still permits an approved one. Persistence
and UI wiring are compiled in the Android build. Device verification checks live toggling, blocked
IPP/eSCL/raw access from the Mac, allowed IPP access, activity logging, and restoration of open mode.
