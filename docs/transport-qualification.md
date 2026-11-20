# Legacy transport qualification

The Windows 11 x64 probe used Oracle JDK 25.0.5.1, FTP API 3.0.0 with SHA-256 `496782add046e211b5f7a686c483522cca8879048f12286249aa50c8f86b505c`, and Apache MINA FtpServer 1.2.1 bound to IPv4 loopback. The fixture generated an RSA 2048-bit self-signed certificate with SHA-256 and loopback subject names. The direct TLS tests trusted that certificate in their own JSSE context. [Test commands](testing.md#loopback-ftp-and-ftps-compatibility)

| Mode | Observed control negotiation | Active data | Passive data |
| --- | --- | --- | --- |
| Plain FTP | Plain control connection | `PORT`, binary round trip passed | `PASV`, binary round trip passed |
| SSL when available against plain FTP | Server declined `AUTH SSL`, followed by plain FTP | `PORT`, binary round trip passed | `PASV`, binary round trip passed |
| SSL when available against FTPS | `AUTH SSL`, TLS 1.2, `PBSZ 0`, `PROT P` | `PORT`, protected binary round trip passed | `PASV`, protected binary round trip passed |
| Explicit FTPS | `AUTH SSL`, TLS 1.2, `PBSZ 0`, `PROT P` | `PORT`, protected binary round trip passed | `PASV`, protected binary round trip passed |
| Implicit FTPS | TLS 1.2 before FTP commands, `PBSZ 0`, `PROT P` | `PORT`, protected binary round trip passed | `PASV`, protected binary round trip passed |

The upload and download SHA-256 was `fc745af96e9674102571aeb7ddf3fd82c7ce457352569c801bf8e620fb2a797b` in all ten cases. JSSE handshake logs showed TLS 1.2 for all six protected combinations under the pinned JDK's default policy. That policy disables SSLv3, TLS 1.0, TLS 1.1, DTLS 1.0, RC4, DES, MD5 with RSA, DH below 1024 bits, EC below 224 bits, 3DES EDE CBC, anonymous and NULL suites, static ECDH and RSA suites, and SHA-1 handshake signatures. The FTP command name `AUTH SSL` selected a TLS negotiation.

## Failure and SOCKS results

Incorrect FTP credentials returned reply 530. A bound loopback port with no listener produced a connection refusal and left the client disconnected.

`LegacySocksTest` ran each scenario in a fresh JVM and applied the real JFTP proxy preferences. JDK 25 normally bypasses SOCKS for loopback destinations. The fixtures set `socksNonProxyHosts` to an empty value in those JVMs so the local test connection traversed the proxy. The test proxy accepted traffic only for its real MINA FTP control listener.

| SOCKS scenario | Observed result |
| --- | --- |
| Correct username and password | SOCKS5 username/password negotiation and CONNECT succeeded, followed by FTP login, `PWD`, and disconnect |
| Wrong proxy password | Username/password negotiation was rejected and the client stayed disconnected |
| CONNECT refused after successful proxy authentication | The proxy returned reply 5 and the client stayed disconnected |

Run `mvnw.cmd -Dtest=LegacySocksTest test` to repeat the three cases.

## Certificate decision

The extracted Windows desktop smoke connected to a real explicit FTPS listener using an untrusted, valid loopback certificate. The Security Warning identified the unknown issuer. No caused a handshake failure and left the tab disconnected. A new connection showed the warning again. Yes allowed login and the protected `/incoming` listing. The initialized server trust store remained empty after No and its bytes were identical after Yes. The same scenarios passed with the recovered package. [Desktop smoke](testing.md#automated-windows-desktop-smoke)
