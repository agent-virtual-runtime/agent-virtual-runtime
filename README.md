# Agent Virtual Runtime (AVR)

> A lightweight virtual world for AI agents.

Agent Virtual Runtime is a Java 11+ runtime for building centralized AI agents without provisioning a container or VM for every run. It owns the agent loop and exposes a virtual environment made of structured capabilities such as virtual files, commands, skills, and artifacts.

## Status

AVR is in early development (`0.x`). APIs may change between minor releases.

The first vertical slice currently includes:

- a model-neutral agent loop;
- structured capability calls;
- an in-memory virtual workspace;
- `file.read` and `file.write` capabilities;
- immutable artifact snapshots;
- a deterministic scripted model for tests and examples.

AVR does **not** execute arbitrary binaries and does not provide kernel-level sandboxing.

## Requirements

- JDK 11 or later
- Maven 3.6 or later

## Build

```bash
mvn verify
```

## Run the example

```bash
mvn -pl avr-examples -am package
java -cp avr-examples/target/avr-examples-0.1.0-SNAPSHOT.jar:avr-core/target/avr-core-0.1.0-SNAPSHOT.jar:avr-api/target/avr-api-0.1.0-SNAPSHOT.jar:avr-storage-memory/target/avr-storage-memory-0.1.0-SNAPSHOT.jar org.agentvirtualruntime.examples.ReportAgentExample
```

## Minimal API

```java
AgentRuntime runtime = new DefaultAgentRuntime(modelGateway, capabilityRegistry);

AgentResult result = runtime.run(AgentRequest.builder()
        .workspace(workspace)
        .prompt("Read /inputs/data.txt and write a report")
        .build());
```

## Modules

- `avr-api`: stable public contracts and SPIs.
- `avr-core`: default agent loop and capability routing.
- `avr-storage-memory`: in-memory workspace implementation.
- `avr-examples`: runnable examples.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). By participating, you agree to follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Security

Please do not report vulnerabilities in public issues. See [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).

