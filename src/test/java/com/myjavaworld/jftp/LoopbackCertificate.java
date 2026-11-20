package com.myjavaworld.jftp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.ftpserver.ssl.SslConfiguration;
import org.apache.ftpserver.ssl.SslConfigurationFactory;

final class LoopbackCertificate {
    private static final String PASSWORD = "synthetic-loopback-store";
    private final Path keyStore;

    LoopbackCertificate(Path directory) throws Exception {
        keyStore = directory.resolve("loopback.jks");
        Path log = directory.resolve("keytool.log");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.isRegularFile(keytool)) {
            keytool = keytool.resolveSibling("keytool.exe");
        }
        Process generate = new ProcessBuilder(keytool.toString(), "-genkeypair",
                "-alias", "loopback", "-dname", "CN=127.0.0.1", "-ext", "SAN=IP:127.0.0.1,DNS:localhost",
                "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA", "-validity", "30",
                "-storetype", "JKS", "-keystore", keyStore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!generate.waitFor(20, TimeUnit.SECONDS)) {
            generate.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new IOException("keytool timed out creating the synthetic loopback certificate");
        }
        if (generate.exitValue() != 0) {
            throw new IOException("keytool failed creating the synthetic loopback certificate: " + Files.readString(log));
        }
    }

    SslConfiguration serverConfiguration() throws Exception {
        SslConfigurationFactory tls = new SslConfigurationFactory();
        tls.setKeystoreFile(keyStore.toFile());
        tls.setKeystoreType("JKS");
        tls.setKeystorePassword(PASSWORD);
        return tls.createSslConfiguration();
    }

    SSLContext trustedClientContext() throws Exception {
        KeyStore trusted = KeyStore.getInstance("JKS");
        try (var input = Files.newInputStream(keyStore)) {
            trusted.load(input, PASSWORD.toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("SSL");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
