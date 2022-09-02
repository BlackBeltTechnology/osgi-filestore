package hu.blackbelt.osgi.filestore.security;

import java.security.Key;

public interface KeyProvider {

    Key getPublicKey();

    Key getPrivateKey();

}
