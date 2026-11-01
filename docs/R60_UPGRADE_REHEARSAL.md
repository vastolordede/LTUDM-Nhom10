# R60: preserve local state on Windows app upgrade

The unpacked Windows app-image is separate from the user's
`%USERPROFILE%\.fileshare-p2p` state directory. Test on a disposable machine
with a real account and existing share/resume files; the packaging command
must NOT delete this directory.

Before replacing app-image:

```
python tools/check_upgrade_state.py snapshot --snapshot C:\tmp\p2p-state-before.json
```

Close the old app, replace program directory only, start new app, log in,
check Peer ID, Tracker settings, shared-file restore and resume semantics.
Then run:

```
python tools/check_upgrade_state.py compare --snapshot C:\tmp\p2p-state-before.json
```

A mismatch is **not automatically a data loss bug** if the app legitimately
updated share/transfer state; investigate each affected path before sign-off.
Keep snapshot private: it contains local relative filenames and hashes. Do not
upload to Git or a public PR. Clean-Windows upgrade rehearsal not performed.
