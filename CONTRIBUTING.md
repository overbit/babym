# Contributing

Thanks for looking. Contributions are genuinely welcome.

One thing to set expectations up front: this is a single-maintainer project with
a deliberately narrow scope. Pull requests get reviewed, but the roadmap is
driven by one person, and features outside the scope below will be declined even
if they're well built. That's not a comment on the work — it's to keep a device
people point at their sleeping child small enough to audit.

## Before you write code

**Open an issue first** for anything beyond a typo or a small bug fix. A short
conversation about approach saves an afternoon of work that gets turned down.

Bug fixes with a clear reproduction can go straight to a PR.

## Scope

In scope:

- Reliability of the Wi-Fi Direct connection and stream
- Reducing latency, battery drain, or dropped frames
- Device and Android-version compatibility
- Accessibility
- Reducing the permission surface or dependency count

Out of scope, deliberately:

- Cloud, remote access, or any feature requiring an internet connection
- Analytics, telemetry, or crash reporting SDKs
- Accounts, sign-in, or a backend of any kind
- Recording or storing the stream
- Cry/motion detection, or anything presented as a safety alert

If a change adds a dependency, say why in the PR. Every dependency is code
running next to a camera in a child's room.

## Development setup

```sh
git clone https://github.com/overbit/babym.git
cd babym
./gradlew assembleDebug
```

JDK 17 and the Android SDK. Testing meaningfully needs two physical devices on
Android 13+ — Wi-Fi Direct doesn't work between emulators.

## Before you open the PR

```sh
./gradlew testDebugUnitTest lintDebug
```

Both must pass; CI runs the same checks. Match the surrounding code style rather
than reformatting files you're touching.

## Pull requests

- One logical change per PR
- Describe what you changed and why; link the issue
- Say which devices and Android versions you tested on — this matters more than
  usual for a P2P app
- Include a screen recording for anything that changes the UI

Reviews usually land within a week. A nudge after that is fine.

## Reporting bugs

Use the issue templates. For connection problems, device models and Android
versions on **both** ends are the most useful thing you can give.

Security issues go through [SECURITY.md](SECURITY.md), not the issue tracker.

## Licensing

By contributing, you agree your contributions are licensed under the
[Apache License 2.0](LICENSE), consistent with the rest of the project.
