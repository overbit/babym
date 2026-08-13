# Security Policy

babym streams live video and audio of a child between two devices. Security
issues here matter more than the project's size suggests, and they are taken
seriously.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Report privately via GitHub's
[private vulnerability reporting](https://github.com/overbit/babym/security/advisories/new).

<!-- TODO: enable this at Settings → Code security → Private vulnerability
     reporting. If you'd rather take reports by email, replace the link above
     with an address you actually monitor. -->

Useful things to include: affected version, the device models and Android
versions involved, reproduction steps, and what an attacker gains.

## What to expect

| | |
| --- | --- |
| Acknowledgement | Within 5 days |
| Initial assessment | Within 14 days |
| Fix or mitigation plan | Communicated once assessed |

This is a volunteer-maintained project and these are good-faith targets, not
contractual guarantees. If you haven't heard back in two weeks, please ping the
thread.

Reporters are credited in the release notes and the advisory unless they ask not
to be.

## Supported versions

Only the latest release receives security fixes.

## In scope

- Unauthorised access to the video or audio stream by a third device
- Weaknesses in device pairing or authentication
- Stream data leaving the direct peer-to-peer link
- Anything that lets a nearby attacker join, hijack, or observe a session

## Out of scope

- Attacks requiring physical access to an already-unlocked paired device
- Vulnerabilities in Android's own Wi-Fi Direct stack (report those to Google)
- Denial of service through radio interference or jamming — inherent to the
  medium, and documented as a limitation in the README

## Known design limitations

These are properties of the design rather than bugs, and they are stated here so
nobody has to discover them the hard way:

- Wi-Fi Direct security depends on the underlying WPA2 handshake between the two
  devices. A monitor is only as private as that link.
- There is no failure alarm. If the stream drops, the app cannot notify you on
  another channel.

<!-- TODO: state plainly whether the stream is encrypted above the Wi-Fi Direct
     layer. If it isn't, say so here rather than leaving it ambiguous — being
     honest about this earns more trust than being vague. -->
