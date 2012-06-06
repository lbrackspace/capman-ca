package org.rackspace.capman.tools.ca;

import java.security.NoSuchProviderException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.math.BigInteger;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bouncycastle.asn1.x509.RSAPublicKeyStructure;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.jce.provider.JCERSAPrivateCrtKey;
import org.bouncycastle.jce.provider.JCERSAPublicKey;
import org.rackspace.capman.tools.ca.exceptions.ConversionException;
import org.rackspace.capman.tools.ca.exceptions.NotAnRSAKeyException;
import org.rackspace.capman.tools.ca.exceptions.PemException;
import org.rackspace.capman.tools.ca.primitives.RsaPair;
import org.rackspace.capman.tools.ca.exceptions.NoSuchAlgorithmException;
import org.rackspace.capman.tools.ca.primitives.Debug;
import org.bouncycastle.jce.provider.HackedProviderAccessor;
import org.rackspace.capman.tools.ca.exceptions.RsaException;
import org.rackspace.capman.tools.ca.primitives.RsaConst;

public class RSAKeyUtils {

    public static final int DEFAULT_KEY_SIZE = 2048;

    static {
        RsaConst.init();
    }
    private static final BigInteger m16bit = new BigInteger("10001", 16);

    @Deprecated
    public static RsaPair genRSAPair(int bits, int certainity) throws NoSuchAlgorithmException {
        return RsaPair.getInstance(bits, certainity);
    }

    @Deprecated
    public static List<String> verifyKeyAndCert(KeyPair kp, X509Certificate cert) {
        List<String> errorList = new ArrayList<String>();
        RsaPair rp;
        try {
            rp = new RsaPair(kp);
        } catch (ConversionException ex) {
            errorList.add("Error converting Rsa KeyPair. Class cast exception");
            return errorList;
        }
        return verifyKeyAndCert(rp, cert);
    }

    @Deprecated
    public static List<String> verifyKeyAndCert(RsaPair rp, X509Certificate cert) {
        List<String> errorList = new ArrayList<String>();
        JCERSAPublicKey certPub = null;
        JCERSAPublicKey keyPub = null;
        try {
            Object obj = rp.toJavaSecurityKeyPair().getPublic();
            String objInfo = Debug.classLoaderInfo(obj.getClass());
            String jpkInfo = Debug.classLoaderInfo(JCERSAPublicKey.class);
            keyPub = (JCERSAPublicKey) obj;
        } catch (NotAnRSAKeyException ex) {
            errorList.add("privateKey or publicKey was null ");
            return errorList;
        } catch (ClassCastException ex) {
            errorList.add("privateKey pair did not decode correctly");
        }

        try {
            certPub = (JCERSAPublicKey) cert.getPublicKey();
        } catch (ClassCastException ex) {
            errorList.add("Error could not retrieve public key from Cert");
            return errorList;
        }

        if (!certPub.getModulus().equals(keyPub.getModulus())) {
            errorList.add("Error cert and key Modulus mismatch");
        }

        if (!certPub.getPublicExponent().equals(keyPub.getPublicExponent())) {
            errorList.add("Error cert and key public exponents mismatch");
        }
        try {
            cert.checkValidity();
        } catch (CertificateExpiredException ex) {
            errorList.add("Error cert Expired");
        } catch (CertificateNotYetValidException ex) {
            errorList.add("Error cert not yet valid");
        } catch (RuntimeException ex) {
            errorList.add("Unable to check date validity of Cert");
        }


        return errorList;
    }

    @Deprecated
    public static List<String> verifyKeyAndCert(byte[] keyPem, byte[] certPem) {
        List<String> errorList = new ArrayList<String>();
        RsaPair rp;
        KeyPair kp = null;
        JCERSAPublicKey certPub = null;
        JCERSAPublicKey keyPub = null;
        X509Certificate cert = null;
        try {
            Object obj = PemUtils.fromPem(keyPem);
            if (obj instanceof JCERSAPrivateCrtKey) {
                kp = HackedProviderAccessor.newKeyPair((JCERSAPrivateCrtKey) obj);
            } else {
                kp = (KeyPair) PemUtils.fromPem(keyPem);
            }
            keyPub = (JCERSAPublicKey) kp.getPublic();
        } catch (PemException ex) {
            errorList.add("Error decoding Key from Pem Data");
        } catch (ClassCastException ex) {
            errorList.add("Error key Pem Data did not decode to an RSA Private Key");
        }

        try {
            cert = (X509Certificate) PemUtils.fromPem(certPem);
        } catch (PemException ex) {
            errorList.add("Error decoding Cert from Pem Data");
        } catch (ClassCastException ex) {
            errorList.add("Error cert Pem data did not decode to an RSA Private Key");
        }

        if (kp == null || cert == null) {
            return errorList;
        }
        try {
            rp = new RsaPair(kp);
        } catch (ConversionException ex) {
            errorList.add("Error converting keypair");
            return errorList;
        }

        return verifyKeyAndCert(rp, cert);
    }

    public static String shortPub(Object obj) {
        String out = null;
        BigInteger n;
        BigInteger e;
        if (obj instanceof JCERSAPublicKey) {
            JCERSAPublicKey jk = (JCERSAPublicKey) obj;
            n = jk.getModulus().mod(m16bit);
            e = jk.getPublicExponent();
            return String.format("(%s,%s)", e, n);
        } else if (obj instanceof RSAKeyParameters) {
            RSAKeyParameters rp = (RSAKeyParameters) obj;
            n = rp.getModulus().mod(m16bit);
            e = rp.getExponent();
            return String.format("(%s,%s)", e, n);
        } else if (obj instanceof RSAPublicKeyStructure) {
            RSAPublicKeyStructure rs = (RSAPublicKeyStructure) obj;
            n = rs.getModulus().mod(m16bit);
            e = rs.getPublicExponent();
            return String.format("(%s,%s)", e, n);
        } else {
            return String.format("(%s,%s)", "None", "None");
        }
    }

    public static KeyPair genKeyPair(int keySize) throws RsaException {
        SecureRandom sr;
        KeyPairGenerator kpGen;
        try {
            sr = SecureRandom.getInstance("SHA1PRNG");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new RsaException("Could not generate RSA Key", ex);
        }
        try {
            kpGen = KeyPairGenerator.getInstance("RSA", "BC");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new RsaException("No such Algo RSA");
        } catch (NoSuchProviderException ex) {
            throw new RsaException("No such Provider BC");
        }
        kpGen.initialize(keySize);
        KeyPair kp = kpGen.generateKeyPair();
        return kp;
    }

    public static String shortKey(Object obj) {
        if (obj == null) {
            return "null";
        } else if (obj instanceof JCERSAPublicKey) {
            JCERSAPublicKey pubKey;
            pubKey = (JCERSAPublicKey) obj;
            String shortMod = pubKey.getModulus().mod(m16bit).toString(16);
            String shortE = pubKey.getPublicExponent().mod(m16bit).toString(16);
            return String.format("(%s,%s)", shortMod, shortE);
        } else if (obj instanceof JCERSAPrivateCrtKey) {
            JCERSAPrivateCrtKey privKey = (JCERSAPrivateCrtKey) obj;
            JCERSAPublicKey pubKey = HackedProviderAccessor.newJCERSAPublicKey(privKey);
            return shortKey(pubKey);
        } else if (obj instanceof KeyPair) {
            KeyPair kp = (KeyPair) obj;
            return shortKey(kp.getPrivate());
        }
        return "NaK";
    }

    public static int modSize(Object obj) {
        if (obj == null) {
            return -1;
        } else if (obj instanceof JCERSAPublicKey) {
            JCERSAPublicKey pubKey;
            pubKey = (JCERSAPublicKey) obj;
            BigInteger modulus = pubKey.getModulus();
            return modulus.bitLength();
        } else if (obj instanceof JCERSAPrivateCrtKey) {
            JCERSAPrivateCrtKey privKey = (JCERSAPrivateCrtKey) obj;
            JCERSAPublicKey pubKey = HackedProviderAccessor.newJCERSAPublicKey(privKey);
            return modSize(pubKey);
        } else if (obj instanceof KeyPair) {
            KeyPair kp = (KeyPair) obj;
            return modSize(kp.getPrivate());
        } else {
            return -1;
        }
    }
}
