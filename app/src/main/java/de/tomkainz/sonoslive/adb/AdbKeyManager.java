/*
 * Copyright 2021 Muntashir Al-Islam
 * Copyright (c) 2026 tomkainz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Key and certificate handling follows the example app of libadb-android
 * (dual licensed GPL-3.0-or-later OR Apache-2.0, used here under Apache-2.0).
 */

package de.tomkainz.sonoslive.adb;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Holds this app's self-issued ADB key. Generated once, then loaded
 * from app storage, so the phone keeps the pairing.
 *
 * Key and certificate storage follows the example of libadb-android
 * (MuntashirAkon, see the license header).
 */
public class AdbKeyManager extends AbsAdbConnectionManager {

    private static AdbKeyManager instance;

    public static AbsAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (instance == null) {
            instance = new AdbKeyManager(context.getApplicationContext());
        }
        return instance;
    }

    private PrivateKey privateKey;
    private Certificate certificate;

    private AdbKeyManager(@NonNull Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        privateKey = readPrivateKey(context);
        certificate = readCertificate(context);
        if (privateKey == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair pair = generator.generateKeyPair();
            PublicKey publicKey = pair.getPublic();
            privateKey = pair.getPrivate();

            String subject = "CN=Sunus";
            String algorithm = "SHA512withRSA";
            // 10 years; the key should outlast the pairing
            long expiry = System.currentTimeMillis() + 10L * 365 * 86400_000L;

            CertificateExtensions extensions = new CertificateExtensions();
            extensions.set("SubjectKeyIdentifier",
                    new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
            X500Name name = new X500Name(subject);
            Date notBefore = new Date();
            Date notAfter = new Date(expiry);
            extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

            X509CertInfo info = new X509CertInfo();
            info.set("version", new CertificateVersion(2));
            info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
            info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
            info.set("subject", new CertificateSubjectName(name));
            info.set("key", new CertificateX509Key(publicKey));
            info.set("validity", new CertificateValidity(notBefore, notAfter));
            info.set("issuer", new CertificateIssuerName(name));
            info.set("extensions", extensions);

            X509CertImpl cert = new X509CertImpl(info);
            cert.sign(privateKey, algorithm);
            certificate = cert;

            writePrivateKey(context, privateKey);
            writeCertificate(context, certificate);
        }
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "Sunus";
    }

    @Nullable
    private static Certificate readCertificate(@NonNull Context context) throws Exception {
        File file = new File(context.getFilesDir(), "adb_cert.pem");
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static void writeCertificate(@NonNull Context context, @NonNull Certificate certificate)
            throws Exception {
        File file = new File(context.getFilesDir(), "adb_cert.pem");
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            encoder.encode(certificate.getEncoded(), out);
            out.write('\n');
            out.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private static PrivateKey readPrivateKey(@NonNull Context context) throws Exception {
        File file = new File(context.getFilesDir(), "adb_private.key");
        if (!file.exists()) return null;
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < bytes.length) {
                int r = in.read(bytes, off, bytes.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static void writePrivateKey(@NonNull Context context, @NonNull PrivateKey privateKey)
            throws IOException {
        File file = new File(context.getFilesDir(), "adb_private.key");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(privateKey.getEncoded());
        }
    }
}
