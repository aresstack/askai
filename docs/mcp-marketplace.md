# MCP marketplace

`:mcp-marketplace` (package `com.aresstack.mcp.*`) is the marketplace integration taken over
from the provided source drop — it was integrated, not rewritten.

## What it does

- `McpMarketplaceService` + clients (`BuiltInMcpMarketplaceClient`, `RegistryMarketplaceClient`,
  `GitHubOrgMarketplaceClient`) list installable MCP servers from configurable sources.
- `McpServerImportService` + `McpInstallOptionMapper` turn a marketplace entry into a local
  `McpServerConfiguration` (transport, command, approval policy).
- `McpMarketplaceBrowserPanel` is the Swing browsing/import surface; `McpMarketplaceDemo` runs it
  standalone.

## Binding rules

- **Installation and runtime activation are strictly separate.** Importing/selecting an entry only
  produces a stored configuration — it NEVER starts a foreign process. Activation is an explicit,
  separate user action in the host.
- GitHub tokens come from `GitHubTokenProvider`; they are never logged and never persisted into
  marketplace entries.
- No network access happens at build/test time; tests run against local fixtures.

## Verification

`McpMarketplaceServiceTest`, `McpInstallOptionMapperTest` (unit, no network).
