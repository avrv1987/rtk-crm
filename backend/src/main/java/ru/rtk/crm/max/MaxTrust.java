package ru.rtk.crm.max;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

// Доверенные корни только для клиента MAX: стандартные корни JDK плюс сертификаты из каталога (корневой сертификат
// Минцифры, который требует platform-api2.max.ru). Общий cacerts JVM не меняется, остальные клиенты CRM его не видят.
// Проверка сертификатов не отключается ни в каком режиме.
final class MaxTrust {
    private MaxTrust() {
    }

    static SSLContext sslContext(Path directory) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (X509Certificate certificate : jdkRoots()) {
                store.setCertificateEntry("jdk-" + index++, certificate);
            }
            for (X509Certificate certificate : certificates(directory)) {
                store.setCertificateEntry("extra-" + index++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Не удалось подготовить доверенные сертификаты для клиента MAX", exception);
        }
    }

    // Файлы *.pem, *.crt, *.cer из каталога; в одном файле может быть цепочка. Нет каталога — нет дополнительных корней.
    static List<X509Certificate> certificates(Path directory) throws IOException, GeneralSecurityException {
        List<X509Certificate> result = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return result;
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".pem") || name.endsWith(".crt") || name.endsWith(".cer")) || !Files.isRegularFile(file)) {
                    continue;
                }
                try (InputStream input = Files.newInputStream(file)) {
                    for (Certificate certificate : factory.generateCertificates(input)) {
                        result.add((X509Certificate) certificate);
                    }
                }
            }
        }
        return result;
    }

    private static List<X509Certificate> jdkRoots() throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        List<X509Certificate> roots = new ArrayList<>();
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                roots.addAll(List.of(x509.getAcceptedIssuers()));
            }
        }
        return roots;
    }
}
