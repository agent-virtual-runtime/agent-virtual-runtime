# Contributing

Thank you for helping build Agent Virtual Runtime.

## Development

1. Use JDK 11 or later.
2. Run `mvn verify` before submitting a pull request.
3. Add tests for behavior changes.
4. Keep `avr-api` free of framework-specific dependencies.
5. Sign commits with `git commit -s` to certify the Developer Certificate of Origin.

New lightweight `Workspace` implementations belong in the existing `avr-storage` module and `com.avr.storage` package. Do not create one Maven module per implementation. A separate adapter module is justified only when an optional vendor SDK would otherwise become a transitive dependency for every storage user.

Use Lombok `@Getter` for immutable data objects and `@Getter`/`@Setter` for mutable configuration beans. Do not apply `@Data` to public API or credential-bearing configuration classes; keep validation, defensive copies and computed methods explicit.

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
📝 docs: explain tool lifecycle
♻️ refactor: separate LLM protocol code
```

Keep each commit focused on one logical change. Use an imperative, concise subject and explain motivation or compatibility impact in the body when needed.

Public API changes should include compatibility notes. Large architectural changes should begin as a discussion or ADR.
