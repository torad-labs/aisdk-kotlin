# Security Policy

Report vulnerabilities through GitHub private vulnerability reporting for this repository.

Do not open public issues for suspected credential leaks, supply-chain problems, or exploitable behavior.

This artifact includes network-capable provider implementations, including Ktor-backed providers, Gateway, and OpenAI-compatible clients. Treat provider configuration as security-sensitive: do not commit API keys, prefer environment or secret-store injection, and report credential exposure, request-signing, retry, logging, redaction, or transport issues privately.
