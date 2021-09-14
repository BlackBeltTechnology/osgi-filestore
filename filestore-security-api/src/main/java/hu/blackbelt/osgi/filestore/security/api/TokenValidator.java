package hu.blackbelt.osgi.filestore.security.api;

import hu.blackbelt.osgi.filestore.security.api.exceptions.InvalidTokenException;

public interface TokenValidator {

    Token<UploadClaim> parseUploadToken(String tokenString) throws InvalidTokenException;

    Token<DownloadClaim> parseDownloadToken(String tokenString) throws InvalidTokenException;
}
