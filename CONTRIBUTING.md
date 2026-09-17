# Contributing

Thank you for helping build Agent Virtual Runtime.

## Development

1. Use JDK 11 or later.
2. Run `mvn verify` before submitting a pull request.
3. Add tests for behavior changes.
4. Keep `avr-api` free of framework-specific dependencies.
5. Sign commits with `git commit -s` to certify the Developer Certificate of Origin.

## Commit messages

This project uses [Gitmoji](https://gitmoji.dev/) commit messages:

```text
<emoji> <type>: <short imperative description>
```

Examples:

```text
✨ feat: add workspace snapshot support
🐛 fix: reject relative virtual paths
✅ test: cover artifact entrypoint validation
📝 docs: explain capability lifecycle
♻️ refactor: separate model gateway contracts
```

Keep each commit focused on one logical change. Use an imperative, concise subject and explain motivation or compatibility impact in the body when needed.

Public API changes should include compatibility notes. Large architectural changes should begin as a discussion or ADR.
