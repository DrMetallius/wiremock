package com.github.tomakehurst.wiremock.jetty94;

import com.github.tomakehurst.wiremock.common.BrowserProxySettings;
import com.github.tomakehurst.wiremock.common.HttpsSettings;
import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.common.ssl.KeyStoreSettings;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.http.ssl.CertificateAuthority;
import com.github.tomakehurst.wiremock.http.ssl.CertificateGenerationUnsupportedException;
import com.github.tomakehurst.wiremock.http.ssl.X509KeyStore;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import static com.github.tomakehurst.wiremock.common.Exceptions.throwUnchecked;

public class SslContexts {

    public static ManInTheMiddleSslConnectHandler buildManInTheMiddleSslConnectHandler(Options options) {
        return new ManInTheMiddleSslConnectHandler(
                new SslConnectionFactory(
                        buildManInTheMiddleSslContextFactory(options.httpsSettings(), options.browserProxySettings(), options.notifier()),
                                /*
                                If the proxy CONNECT request is made over HTTPS, and the
                                actual content request is made using HTTP/2 tunneled over
                                HTTPS, and an exception is thrown, the server blocks for 30
                                seconds before flushing the response.

                                To fix this, force HTTP/1.1 over TLS when tunneling HTTPS.

                                This also means the HTTP connector does not need the alpn &
                                h2 connection factories as it will not use them.

                                Unfortunately it has proven too hard to write a test to
                                demonstrate the bug; it requires an HTTP client capable of
                                doing ALPN & HTTP/2, which will only offer HTTP/1.1 in the
                                ALPN negotiation when using HTTPS for the initial CONNECT
                                request but will then offer both HTTP/1.1 and HTTP/2 for the
                                actual request (this is how curl 7.64.1 behaves!). Neither
                                Apache HTTP 4, 5, 5 Async, OkHttp, nor the Jetty client
                                could do this. It might be possible to write one using
                                Netty, but it would be hard and time consuming.
                                 */
                        HttpVersion.HTTP_1_1.asString()
                )
        );
    }

    public static SslContextFactory.Server buildHttp2SslContextFactory(HttpsSettings httpsSettings) {
        SslContextFactory.Server sslContextFactory = SslContexts.defaultSslContextFactory(httpsSettings.keyStore());
        sslContextFactory.setKeyManagerPassword(httpsSettings.keyManagerPassword());
        setupClientAuth(sslContextFactory, httpsSettings);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        sslContextFactory.setProvider("Conscrypt");
        return sslContextFactory;
    }

    public static SslContextFactory.Server buildManInTheMiddleSslContextFactory(HttpsSettings httpsSettings, BrowserProxySettings browserProxySettings, final Notifier notifier) {
        KeyStoreSettings browserProxyCaKeyStore = browserProxySettings.caKeyStore();
        SslContextFactory.Server sslContextFactory = buildSslContextFactory(notifier, browserProxyCaKeyStore, httpsSettings.keyStore());
        setupClientAuth(sslContextFactory, httpsSettings);
        return sslContextFactory;
    }

    private static void setupClientAuth(SslContextFactory.Server sslContextFactory, HttpsSettings httpsSettings) {
        if (httpsSettings.hasTrustStore()) {
            sslContextFactory.setTrustStorePath(httpsSettings.trustStorePath());
            sslContextFactory.setTrustStorePassword(httpsSettings.trustStorePassword());
        }
        sslContextFactory.setNeedClientAuth(httpsSettings.needClientAuth());
    }

    private static SslContextFactory.Server buildSslContextFactory(Notifier notifier, KeyStoreSettings browserProxyCaKeyStore, KeyStoreSettings defaultHttpsKeyStore) {
        if (browserProxyCaKeyStore.exists()) {
            X509KeyStore existingKeyStore = toX509KeyStore(browserProxyCaKeyStore);
            return certificateGeneratingSslContextFactory(notifier, browserProxyCaKeyStore, existingKeyStore);
        } else {
            try {
                X509KeyStore newKeyStore = buildKeyStore(browserProxyCaKeyStore);
                return certificateGeneratingSslContextFactory(notifier, browserProxyCaKeyStore, newKeyStore);
            } catch (Exception e) {
                notifier.error("Unable to generate a certificate authority", e);
                return defaultSslContextFactory(defaultHttpsKeyStore);
            }
        }
    }

    private static SslContextFactory.Server defaultSslContextFactory(KeyStoreSettings defaultHttpsKeyStore) {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        setupKeyStore(sslContextFactory, defaultHttpsKeyStore);
        return sslContextFactory;
    }

    private static SslContextFactory.Server certificateGeneratingSslContextFactory(Notifier notifier, KeyStoreSettings browserProxyCaKeyStore, X509KeyStore newKeyStore) {
        SslContextFactory.Server sslContextFactory = new CertificateGeneratingSslContextFactory(newKeyStore, notifier);
        setupKeyStore(sslContextFactory, browserProxyCaKeyStore);
        // Unlike the default one, we can insist that the keystore password is the keystore password
        sslContextFactory.setKeyStorePassword(browserProxyCaKeyStore.password());
        return sslContextFactory;
    }

    private static void setupKeyStore(SslContextFactory.Server sslContextFactory, KeyStoreSettings keyStoreSettings) {
        sslContextFactory.setKeyStore(keyStoreSettings.loadStore());
        sslContextFactory.setKeyStorePassword(keyStoreSettings.password());
        sslContextFactory.setKeyStoreType(keyStoreSettings.type());
    }

    private static X509KeyStore toX509KeyStore(KeyStoreSettings browserProxyCaKeyStore) {
        try {
            return new X509KeyStore(browserProxyCaKeyStore.loadStore(), browserProxyCaKeyStore.password().toCharArray());
        } catch (KeyStoreException e) {
            // KeyStore must be loaded here, should never happen
            return throwUnchecked(e, null);
        }
    }

    private static X509KeyStore buildKeyStore(KeyStoreSettings browserProxyCaKeyStore) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, CertificateGenerationUnsupportedException {
        final CertificateAuthority certificateAuthority = CertificateAuthority.generateCertificateAuthority();
        KeyStore keyStore = KeyStore.getInstance(browserProxyCaKeyStore.type());
        char[] password = browserProxyCaKeyStore.password().toCharArray();
        keyStore.load(null, password);
        keyStore.setKeyEntry("wiremock-ca", certificateAuthority.key(), password, certificateAuthority.certificateChain());

        browserProxyCaKeyStore.getSource().save(keyStore);

        return new X509KeyStore(keyStore, password);
    }
}
