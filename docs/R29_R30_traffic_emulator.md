# R29/R30: actual concurrent load in TrafficEmulator

`gradle :peer-client:emulator -PemuArgs="3 demo_user secret 10100 90 download"`

Start with **disposable test accounts and local or isolated Tracker**; do not put
production credentials in scripts, CI logs or screenshot attachments.
Each virtual peer uses a distinct identity and upload port. The emulator
releases download workers concurrently after a CountDownLatch confirms all
virtual peers have published files. It downloads a real shared binary, verifies
whole-file SHA-256, and records result counts. It now sets the uploader Peer ID
before opening its server, matching R43 access ticket authorization.

Before running set both Tracker ticket signing key and corresponding upload Peer
public key securely (see R43 doc); otherwise fail-closed download is expected.

Limitations: one process on one machine is not a 3-machine network test; a file
may be served by only one of the available peers depending on scheduling.
Collect source-level transfer stats on 3 machines separately for R20/R21/R51.
No load tests were run against a real server in this environment.
