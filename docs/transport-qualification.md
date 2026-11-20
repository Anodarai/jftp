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

On 2026-11-20, the modernized FTP adapter passed all ten combinations against the same loopback fixtures and default JDK policy. The adapter test checked the active or passive command, TLS negotiation commands, matching binary bytes on the server and after download, and the refreshed directory listing. Implicit FTPS used the site's separately configured implicit port. [Negotiation and payload tests](../src/test/java/com/myjavaworld/jftp/LegacyFtpsTest.java)

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

## Multi-file abort and tab isolation baseline

The Windows smoke used a MINA account limited to 16 KiB/s download speed. The first tab remained logged in with its original listing while a second tab received reply 530, recovered through a valid favorite, and began downloading two selected files. The test switched to the first tab while the second tab's first file was partial, confirmed the first tab was usable, then returned to the second tab and pressed F12. The second tab reported the abort and the server returned reply 426 for the interrupted transfer. The second file never started. The first tab's listing and log stayed intact. This automated probe passed with the recovered package.

## Failure paths recorded before refactoring

On 2026-11-20, both scenarios passed in the packaged desktop smoke against the recovered Windows ZIP. Its SHA-256 was `40bfcbf70bd2c5692c709f639b1fa63895e5c6509bd925e325f6fd72eee87d40`. The probe used the pinned JDK and MINA on IPv4 loopback.

With MINA configured without its `LIST` command, login, `CWD /incoming`, and `PWD` succeeded. The first `LIST` returned reply 502. The recovered client logged the reply and cleared its busy state. The tab stayed connected and kept the saved-site title, with an empty remote pane and no published working directory. Disconnect still worked.

With the same download rate limit, aborting Download and Unzip produced reply 426. With the current directory selected for download and extraction, the client retained the partial ZIP, extracted no payload, and displayed an extraction error. Dismissing the error returned to the session. [PRD failure observations](PRD.md#12-further-notes)

## Closing a tab during a live listing

The packaged test held a real `LIST` at the loopback server, closed the requesting tab, opened a replacement, then released the server. Before the coordinator, the close waited for the server on the event dispatch thread and a late callback dereferenced the closed tab's status bar. The coordinator let the tab close while the server was still waiting. After release, the server observed the closed session, the replacement stayed disconnected, and the original connected tab kept its listing. The packaged test also checks for uncaught event-dispatch exceptions. [Packaged session test](../src/test/java/com/myjavaworld/jftp/DesktopSmoke.java)
