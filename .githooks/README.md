# Git hooks

Opt-in, repo-local git hooks. They are **not** enabled by default — Git only
runs hooks from `.git/hooks` unless you point `core.hooksPath` at this
directory:

```bash
git config core.hooksPath .githooks
```

## `pre-commit`

Runs [gitleaks](https://github.com/gitleaks/gitleaks) over staged changes and
blocks a commit that would introduce a credential-shaped secret. It is a
shift-left companion to GitHub's server-side secret scanning and push
protection (the backstop) — a second, earlier layer per the DevSecOps "scan
pre-commit **and** on push" guidance. If gitleaks is not installed the hook
skips with a note rather than failing, so it stays friction-free to adopt.
