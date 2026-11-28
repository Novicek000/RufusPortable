package pl.rufusportable;

import java.io.IOException;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** OpenSSL SHA-256/RSA resource signatures from Rufus pki.c (GPL-3.0-or-later).
 * Java uses standard big-endian RSA encoding, so the Windows CryptoAPI reversals are unnecessary. */
final class RufusSignature {
    private static final String KEY="MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtkB90Zh7gZ6+Iw8yXVVgxr+0QbtDG/Hh5vkr1t0RUOi5PxmXXqeLSjDGdlhyHKz/ofiWbFFdExHjWxGC9Zpp5CiXD8ofAuoffdz5/HkvYf+ORWBlujeb3kkFaqj9cNAMebbXgapUw8ZKh6BF7srV1cXCrIZCs1gn0kO5N/LmdWYXU9A40MZXwlU2okOH6iTwluw03XlNgFSdhIGnzwylfNZj+npmMKlQ7vDl+KItrPwkIf7v6NNvDiewZCKVPm2mZpfGmMJHs5hpTbG1029D9delE16MKE9iTgFICmOJ58o0qn0vu3DgMbs5SaPSyS6mMFSaXE1YF9n8OkPmjioY6QIDAQAB";
    private static PublicKey publicKey() throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(KEY)));
    }
    static void verify(byte[] content,byte[] signature) throws IOException {
        if(content==null || signature==null || signature.length!=256)throw new IOException("Nieprawidłowy podpis zasobu Rufusa.");
        try {
            Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(publicKey());verifier.update(content);
            if(!verifier.verify(signature))throw new IOException("Podpis zasobu Rufusa nie jest prawidłowy.");
        }catch(GeneralSecurityException error){throw new IOException("Nie można zweryfikować podpisu zasobu Rufusa.",error);}
    }
    private RufusSignature(){}
}
