# enterprise-ai-secrets

Unchanged copy of the secret API of [aresstack/enterprise-ai-client](https://github.com/aresstack/enterprise-ai-client):
the package `com.aresstack.enterpriseai.adyton` (`SecretProvider`, `SecretVault`, `SecretEntry`, `SecretMaterial`,
`VaultEntry`, `VaultEntryDraft`, `SecretUnavailableException`, `SecretFunction`) and
`com.aresstack.enterpriseai.astu.security.SecretRef`, with their unit tests.

Taken from aresstack/space-harvester commit `e8621f4609ddcb6e359b4866dd69c0b94bfe6d62`
(`enterprise-ai-sources`, itself an unchanged copy of the client). Only these files are needed by
[`enterprise-ai-keepass`](../enterprise-ai-keepass/UPSTREAM.md); changes belong upstream.
