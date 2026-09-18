# Changelog

All notable changes will be documented in this file.

## Unreleased

- Consolidated `avr-storage-memory`, `avr-storage-disk` and `avr-storage-object` into the single `avr-storage` module.
- Moved built-in Workspace implementations to the `com.avr.storage` package. Existing imports from `com.avr.storage.memory`, `com.avr.storage.disk` and `com.avr.storage.object` must be updated.
- Persisted artifact manifests and immutable content snapshots in disk and object-backed workspaces so they survive Workspace recreation and process restarts.
- Reserved the internal `/.avr` namespace and excluded it from agent-visible file operations.

## 1.0.0 - 2026-09-17

- Added the model-neutral agent loop, sync/async API and lifecycle events.
- Added execution context, authorization policy, Skill injection and multi-agent delegation.
- Added memory and disk virtual workspaces with artifact entrypoints.
- Added safe virtual commands and an embeddable HTTP transport.
- Added immutable artifact contents, browser preview, complete text-file operations and VFS Skill loading.
- Added the high-level `Agent.input` API and business-neutral `WorkspaceProvider` SPI.
- Added a cloud-neutral object storage workspace and provider SPI.
- Added ordered concurrent tool execution, sequential mutation barriers, tool timeouts,
  empty-response retry and a no-progress fuse.
- Added an OpenAI-compatible Chat Completions client with complete tool-call history.
- Established Java 11 compatibility, Apache-2.0 licensing and project governance files.
