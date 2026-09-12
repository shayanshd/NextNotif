# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

People who want to keep an older, functional Android phone in a fixed location as a dedicated SIM gateway while carrying a newer Android phone as the receiver.

## Product Purpose

NextNotif connects paired Android phones so SMS messages and incoming-call information received by one phone are immediately available on another. Success means an older sender phone can operate reliably as an appliance while the receiver provides a clear, current interface.

## Positioning

NextNotif reuses an existing Android handset and SIM as the gateway instead of requiring number porting or dedicated carrier hardware.

## Operating Context

- The sender may be an Android 8-era phone that stays plugged in and unattended.
- The receiver is the phone the user carries and interacts with.
- Pairings can use the built-in Firebase relay, a user-supplied Firebase project, or a WebSocket relay.
- The preferred MVP path is FCM/HTTPS while idle, with a temporary authenticated
  live connection only while an answered call is being relayed.
- Full cellular-audio bridging is experimental and must be proven per sender device and firmware.
- Live-call forwarding is an optional sender capability, disabled by default. It may
  be advertised to a receiver only after explicit opt-in and a successful root/helper
  capability check; ordinary SMS and call-information forwarding never requires root.

## Capabilities and Constraints

- Shipped capabilities include many-to-many pairing, SMS forwarding, incoming-call state and caller-ID forwarding, receiver notifications, offline queues, and reconnect behavior.
- The supported baseline is Android 8.0 (API 26) and newer.
- Ordinary third-party apps cannot assume access to cellular call RX/TX audio.
- Remote call control and call-audio experiments require explicit user action and device testing.
- Diagnostic audio samples are measured in memory and discarded; the diagnostic does not save call recordings.
- The Samsung Galaxy A5 SM-A520F on Android 8 is the first reference sender for gateway experiments.

## Brand Commitments

The product name is NextNotif. Interface language is direct, practical, and transparent about connection state, permissions, and limitations.

## Evidence on Hand

- Existing Android app and relay implementations in this repository.
- A two-device end-to-end test history recorded in `roadmap.md`.
- A Samsung Galaxy A5 SM-A520F running Android 8 is available for physical-device testing.
- Controlled hardware experiments established digital `VOICE_DOWNLINK` capture and
  root-only telephony-uplink injection on the reference SM-A520F. Two-way relay works,
  but call audio remains a device-specific beta until the repeatability gate is met.

## MVP First Value

The intended user is mixed-experience rather than developer-only. The onboarding
“aha” moment is a successful test alert from the unattended sender appearing on the
receiver, with both phones showing Ready and no persistent receiver connection. The
target is under five minutes from fresh install, with advanced relay and rooted-audio
details disclosed only when they become relevant.

## Product Principles

- Preserve the reliable SMS and call-notification baseline while experiments remain optional.
- Treat the sender as a narrowly scoped appliance with minimal personal data.
- Measure device capabilities before requesting root or changing firmware.
- Never describe microphone signal as caller audio without a controlled comparison.
- Make sensitive actions visible, intentional, and reversible where the platform allows.
