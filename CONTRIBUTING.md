# Contributing to CLONIX

Thanks for considering a contribution.

## Before you start

- Search existing issues and pull requests before opening a duplicate.
- For substantial behavior or architecture changes, open an issue first so the scope can be discussed.
- Keep security vulnerabilities out of public issues; follow [SECURITY.md](SECURITY.md).
- Never commit credentials, signing keys, tokens, private logs, or personal data.

## Development workflow

1. Fork the repository and create a focused branch.
2. Make the smallest change that fully solves the problem.
3. Follow the existing project structure, naming, formatting, and architecture.
4. Update tests and documentation when behavior changes.
5. Run the checks documented in the README and the repository's CI configuration.
6. Open a pull request with a clear explanation of the problem, the solution, and how it was verified.

## Pull request quality

A good pull request should:

- address one coherent problem;
- avoid unrelated refactors;
- explain user-visible or compatibility impact;
- include reproduction steps for bug fixes where practical;
- include screenshots for meaningful UI changes;
- preserve backwards compatibility unless the change explicitly requires otherwise;
- keep generated files and dependency changes intentional and reviewable.

## Commit messages

Use concise, descriptive commit messages. Conventional-style prefixes such as `fix:`, `feat:`, `docs:`, `test:`, and `refactor:` are welcome when they fit the change.

## Review

Maintainers may request changes for correctness, scope, security, compatibility, documentation, or maintainability. A pull request is ready to merge only after the relevant automated and manual checks pass.

Thank you for helping improve CLONIX.
