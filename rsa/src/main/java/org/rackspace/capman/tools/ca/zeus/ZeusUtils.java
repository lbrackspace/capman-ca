package org.rackspace.capman.tools.ca.zeus;

import org.rackspace.capman.tools.ca.primitives.bcextenders.HackedProviderAccessor;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.Map;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.jce.provider.JCERSAPrivateCrtKey;
import org.bouncycastle.jce.provider.JCERSAPublicKey;
import org.bouncycastle.jce.provider.X509CertificateObject;
import org.rackspace.capman.tools.ca.CertUtils;
import org.rackspace.capman.tools.ca.PemUtils;
import org.rackspace.capman.tools.ca.exceptions.PemException;
import org.rackspace.capman.tools.ca.exceptions.X509PathBuildException;
import org.rackspace.capman.tools.ca.primitives.PemBlock;
import org.rackspace.capman.tools.ca.primitives.RsaConst;
import org.rackspace.capman.tools.ca.zeus.primitives.ErrorEntry;
import org.rackspace.capman.tools.ca.zeus.primitives.ZeusCrtFile;
import org.rackspace.capman.tools.util.ResponseWithExcpetions;
import org.rackspace.capman.tools.util.StaticHelpers;
import org.rackspace.capman.tools.util.X509BuiltPath;
import org.rackspace.capman.tools.util.X509PathBuilder;
import org.rackspace.capman.tools.util.X509ReaderWriter;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.UNREADABLE_CERT;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.UNREADABLE_KEY;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.COULDENT_ENCODE_KEY;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.COULDENT_ENCODE_CERT;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.NO_PATH_TO_ROOT;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.EXPIRED_CERT;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.PREMATURE_CERT;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.KEY_CERT_MISMATCH;
import static org.rackspace.capman.tools.ca.zeus.primitives.ErrorType.SIGNATURE_ERROR;

public class ZeusUtils {

    static {
        RsaConst.init();
    }
    private Set<X509CertificateObject> roots;

    public ZeusUtils() {
        roots = new HashSet<X509CertificateObject>();
    }

    public ZeusUtils(Set<X509CertificateObject> roots) {
        this.roots = roots;
    }

    public ZeusCrtFile buildZeusCrtFile(String keyStr, String userCrtStr, String intermediates, boolean useLbaasValidation) {
        Date date = new Date(System.currentTimeMillis());
        if (!useLbaasValidation) {
            return buildZeusCrtFile(keyStr, userCrtStr, intermediates, date);
        } else {
            return buildZeusCrtFileLbassValidation(keyStr, userCrtStr, intermediates);
        }
    }

    public ZeusCrtFile buildZeusCrtFileLbassValidation(String userKeyStr, String userCrtStr, String intermediates) {
        ZeusCrtFile zcf = new ZeusCrtFile();
        List<ErrorEntry> errors = zcf.getErrors();
        Map<X509CertificateObject, Integer> lineMap = new HashMap<X509CertificateObject, Integer>();
        String zkey = "";
        String zcrt = "";
        String msg;

        KeyPair userKey = parseKey(userKeyStr, zcf.getErrors());
        X509CertificateObject userCrt = decodeCrt(userCrtStr, zcf);
        List<X509CertificateObject> imdCrts = decodeImd(lineMap, intermediates, zcf);

        // Verify key matches cert if both are Present
        if (!zcf.containsErrorTypes(UNREADABLE_CERT, UNREADABLE_KEY)) {
            List<ErrorEntry> keyMatchErrors;
            keyMatchErrors = CertUtils.validateKeyMatchesCrt(userKey, userCrt);
            errors.addAll(keyMatchErrors);
        }

        // If their is a chain veify that the top of the chain signs the users crt
        if (!imdCrts.isEmpty() && userCrt != null) {
            X509CertificateObject subjectCrt = userCrt;
            X509CertificateObject issuerCrt = imdCrts.get(0);
            List<ErrorEntry> crtSignErrors = CertUtils.verifyIssuerAndSubjectCert(issuerCrt, subjectCrt, false);
            if (ErrorEntry.hasFatal(crtSignErrors)) {
                if (lineMap.containsKey(issuerCrt)) {
                    int issuerLineNum = lineMap.get(issuerCrt).intValue();
                    msg = String.format("Error the cert at line %d of the  Chain file does not sign the main cert", issuerLineNum);
                    errors.add(new ErrorEntry(SIGNATURE_ERROR, msg, true, null));
                } else {
                    msg = String.format("Error the cert at the top of the chain file does not sign the main cert");
                    errors.add(new ErrorEntry(SIGNATURE_ERROR, msg, true, null));
                }
            }
            errors.addAll(crtSignErrors);
        }

        ArrayList<ErrorEntry> chainSignErrors = new ArrayList<ErrorEntry>();
        for (int i = 1; i < imdCrts.size(); i++) {
            X509CertificateObject subjectCrt = imdCrts.get(i - 1);
            X509CertificateObject issuerCrt = imdCrts.get(i);
            List<ErrorEntry> crtSignErrors = CertUtils.verifyIssuerAndSubjectCert(issuerCrt, subjectCrt, false);
            if (ErrorEntry.hasFatal(crtSignErrors)) {
                if (lineMap.containsKey(issuerCrt) && lineMap.containsKey(subjectCrt)) {
                    int issuerLineNum = lineMap.get(issuerCrt).intValue();
                    int subjectLineNum = lineMap.get(subjectCrt).intValue();
                    msg = String.format("Error chain out of order Certificate at line %d does not sign crt at line %d", issuerLineNum, subjectLineNum);
                    errors.add(new ErrorEntry(SIGNATURE_ERROR, msg, true, null));
                } else {
                    msg = String.format("Error chain out of order");
                    errors.add(new ErrorEntry(SIGNATURE_ERROR, msg, true, null));
                }
            }
            errors.addAll(crtSignErrors);
        }


        // If there where no errors build the full ZCF object
        if (!ErrorEntry.hasFatal(errors)) {
            StringBuilder sb = new StringBuilder(4096);
            try {
                zkey = PemUtils.toPemString(userKey);
            } catch (PemException ex) {
                errors.add(new ErrorEntry(COULDENT_ENCODE_KEY, "Error encodeing users key", true, ex));
            }
            try {
                sb.append(PemUtils.toPemString(userCrt));
            } catch (PemException ex) {
                errors.add(new ErrorEntry(COULDENT_ENCODE_CERT, "Error encodeing users Crt", true, ex));
            }
            for (X509CertificateObject x509obj : imdCrts) {
                try {
                    String x509Str = PemUtils.toPemString(x509obj);
                    sb.append(x509Str);
                } catch (PemException ex) {
                    if (lineMap.containsKey(x509obj)) {
                        int x509lineNum = lineMap.get(x509obj).intValue();
                        msg = String.format("Error encodeing chain crt at line %d", x509lineNum);
                        errors.add(new ErrorEntry(COULDENT_ENCODE_CERT, msg, true, ex));
                    } else {
                        errors.add(new ErrorEntry(COULDENT_ENCODE_CERT, "Error encodeing chain crt", true, ex));
                    }
                }
            }

            // Also append a NO_PATH_TO_ROOT error if the cert has no known root
            if (!ErrorEntry.hasFatal(errors)) {
                Set<X509CertificateObject> imdSet = new HashSet<X509CertificateObject>(imdCrts);
                X509PathBuilder<X509CertificateObject> pathBuilder = new X509PathBuilder<X509CertificateObject>(roots, imdSet);
                try {
                    X509BuiltPath<X509CertificateObject> builtPath = pathBuilder.buildPath(userCrt);
                } catch (X509PathBuildException ex) {
                    errors.add(new ErrorEntry(NO_PATH_TO_ROOT, "Chain has not path to root", true, ex));
                }
            }

            // If there are still no errors we can set the key and crt
            if (!ErrorEntry.hasFatal(errors)) {
                zcrt = sb.toString();
                zcf.setPrivate_key(zkey);
                zcf.setPublic_cert(zcrt);
            }
        }
        return zcf;
    }

    public ZeusCrtFile buildZeusCrtFile(String keyStr, String userCrtStr, String intermediates, Date date) {
        ZeusCrtFile zcf = new ZeusCrtFile();
        List<ErrorEntry> errors = zcf.getErrors();
        List<PemBlock> blocks;
        KeyPair kp = null;
        X509CertificateObject userCrt = null;
        Object obj;
        // Read Key
        kp = parseKey(keyStr, errors);
        userCrt = parseCert(userCrtStr, errors);

        if (userCrt != null) {
            if (CertUtils.isCertExpired(userCrt, date)) {
                Date after = userCrt.getNotAfter();
                String errorMsg = invalidDateMessage("User cert expired on", after);
                errors.add(new ErrorEntry(EXPIRED_CERT, errorMsg, true, null));
            }

            if (CertUtils.isCertPremature(userCrt, date)) {
                Date before = userCrt.getNotBefore();
                String errorMsg = invalidDateMessage("User cert isn't valid till", date);
                errors.add(new ErrorEntry(PREMATURE_CERT, errorMsg, false, null));
            }
        }

        // Check key and cert match
        if (kp != null && userCrt != null) {
            PublicKey userKey = userCrt.getPublicKey();
            List<ErrorEntry> keyCrtErrors = CertUtils.validateKeyMatchesCert((JCERSAPublicKey) kp.getPublic(), userCrt);
            if (keyCrtErrors.size() > 0) {
                errors.addAll(keyCrtErrors);
                return zcf;
            }
        }
        // Retrieve Intermediates.
        Set<X509CertificateObject> imdSet;
        if (intermediates != null) {
            ResponseWithExcpetions<Set<X509CertificateObject>> resp = X509ReaderWriter.readSet(intermediates);
            imdSet = resp.getReturnObject();
            for (Throwable th : resp.getExceptions()) {
                errors.add(new ErrorEntry(UNREADABLE_CERT, th.getMessage(), false, th));
            }
        } else {
            imdSet = new HashSet<X509CertificateObject>();
        }

        if (userCrt != null) {
            X509PathBuilder<X509CertificateObject> pathBuilder = new X509PathBuilder<X509CertificateObject>(roots, imdSet);
            X509BuiltPath<X509CertificateObject> builtPath;
            try {
                builtPath = pathBuilder.buildPath(userCrt, date);
            } catch (X509PathBuildException ex) {
                errors.add(new ErrorEntry(NO_PATH_TO_ROOT, "No Path to root", true, ex));
                return zcf;
            }
            StringBuilder zcrtString = new StringBuilder(RsaConst.PAGESIZE);
            List<ErrorEntry> certWriteErrors = new ArrayList<ErrorEntry>();
            for (X509CertificateObject x509obj : builtPath.getPath()) {
                try {
                    String x509String = PemUtils.toPemString(x509obj);
                    zcrtString.append(x509String);
                } catch (PemException ex) {
                    certWriteErrors.add(new ErrorEntry(COULDENT_ENCODE_CERT, "Coulden't encode intermediate", true, ex));
                }
                if (certWriteErrors.size() > 0) {
                    errors.addAll(certWriteErrors);
                    return zcf;
                }
            }
            zcf.setPublic_cert(zcrtString.toString());
        }
        if (kp != null) {
            try {
                String privKey = PemUtils.toPemString(kp);
                zcf.setPrivate_key(privKey);
            } catch (PemException ex) {
                errors.add(new ErrorEntry(COULDENT_ENCODE_KEY, ex.getMessage(), true, ex));
                return zcf;
            }
        }
        return zcf;
    }

    public Set<X509CertificateObject> getRoots() {
        return roots;
    }

    public void setRoots(Set<X509CertificateObject> roots) {
        this.roots = roots;
    }

    private static KeyPair parseKey(String keyIn, List<ErrorEntry> errors) {
        KeyPair kp = null;
        List<PemBlock> blocks = PemUtils.parseMultiPem(keyIn);
        Object obj;
        if (blocks.size() < 1) {
            errors.add(new ErrorEntry(UNREADABLE_KEY, "No pemblock found in key String", true, null));
            return kp;
        }

        if (blocks.size() > 1) {
            errors.add(new ErrorEntry(UNREADABLE_KEY, "Multiple pem blocks used in Key", true, null));
            return kp;
        }
        obj = blocks.get(0).getDecodedObject();
        if (obj == null) {
            errors.add(new ErrorEntry(UNREADABLE_KEY, "Unable to parse pemblock to RSA object", true, null));
            return kp;
        }
        if (obj instanceof JCERSAPrivateCrtKey) {
            try {
                obj = HackedProviderAccessor.newKeyPair((JCERSAPrivateCrtKey) obj);
            } catch (InvalidKeySpecException ex) {
                errors.add(new ErrorEntry(UNREADABLE_KEY, "Error when attempting to convert PKCS8 key to PKCS1", true, ex));
            }
        }
        if (!(obj instanceof KeyPair)) {
            errors.add(new ErrorEntry(UNREADABLE_KEY, "Unable to parse pemblock to RSA object", true, null));
            return kp;
        }

        kp = (KeyPair) obj;
        if (!(kp.getPublic() instanceof JCERSAPublicKey) || !(kp.getPrivate() instanceof JCERSAPrivateCrtKey)) {
            errors.add(new ErrorEntry(UNREADABLE_KEY, "KeyPair object was not an RSA keyPair", true, null));
            kp = null;
            return kp;
        }
        return kp;
    }

    private static X509CertificateObject parseCert(String certIn, List<ErrorEntry> errors) {
        X509CertificateObject x509obj = null;
        List<PemBlock> blocks = PemUtils.parseMultiPem(certIn);
        Object obj;
        if (blocks.size() < 1) {
            errors.add(new ErrorEntry(UNREADABLE_CERT, "userCrt did not contain a pem block", true, null));
            return x509obj;
        }

        if (blocks.size() > 1) {
            errors.add(new ErrorEntry(UNREADABLE_CERT, "userCrt contains more then one pem block", true, null));
            return x509obj;
        }
        obj = blocks.get(0).getDecodedObject();
        if ((obj == null) || !(obj instanceof X509CertificateObject)) {
            errors.add(new ErrorEntry(UNREADABLE_CERT, "unable to parse userCrt to a X509Certificate", true, null));
            return x509obj;
        }
        x509obj = (X509CertificateObject) obj;
        return x509obj;
    }

    private static String invalidDateMessage(String premsg, Date dateEdge) {
        String edge = StaticHelpers.getDateString(dateEdge);
        String msg = String.format("%s %s", premsg, edge);
        return msg;
    }

    private static List<X509CertificateObject> decodeImd(Map<X509CertificateObject, Integer> lineMap, String imdStr, ZeusCrtFile zcf) {
        List<ErrorEntry> errors = zcf.getErrors();
        ErrorEntry errorEntry;
        List<X509CertificateObject> imdCrts = new ArrayList<X509CertificateObject>();
        X509CertificateObject x509obj;
        List<PemBlock> blocks = PemUtils.parseMultiPem(imdStr);
        String msg;
        if (imdStr == null || imdStr.length() == 0) {
            return imdCrts;
        }
        for (PemBlock block : blocks) {
            Object obj = block.getDecodedObject();
            if (obj == null) {
                msg = String.format("Empty object at line starting at line %d", block.getLineNum());
                errorEntry = new ErrorEntry(UNREADABLE_CERT, msg, false, null);
                errors.add(errorEntry);
                continue;
            }
            if (!(obj instanceof X509CertificateObject)) {
                msg = String.format("Object at line %d decoded to class %s but was expecting X509CertificateObject", block.getLineNum(), obj.getClass().getName());
                errorEntry = new ErrorEntry(UNREADABLE_CERT, msg, false, null);
                errors.add(errorEntry);
                continue;
            }
            x509obj = (X509CertificateObject) obj;
            imdCrts.add(x509obj);
            lineMap.put(x509obj, new Integer(block.getLineNum()));
        }
        return imdCrts;
    }

    private static X509CertificateObject decodeCrt(String crtStr, ZeusCrtFile zcf) {
        X509CertificateObject crt;
        ErrorEntry errorEntry;
        List<ErrorEntry> errors = zcf.getErrors();
        Object obj;
        try {
            obj = PemUtils.fromPemString(crtStr);
            if (!(obj instanceof X509CertificateObject)) {
                String msg = String.format("crt object decoded to class %s but was expecting X509CertificateObject", obj.getClass().getName());
                errorEntry = new ErrorEntry(UNREADABLE_CERT, msg, true, null);
                errors.add(errorEntry);
                return null;
            }
            crt = (X509CertificateObject) obj;
            return crt;
        } catch (PemException ex) {
            errorEntry = new ErrorEntry(UNREADABLE_CERT, "Unable to read userCrt", true, ex);
            errors.add(errorEntry);
            return null;
        }
    }
}
