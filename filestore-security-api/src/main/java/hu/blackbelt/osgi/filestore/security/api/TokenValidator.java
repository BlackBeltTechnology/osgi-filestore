package hu.blackbelt.osgi.filestore.security.api;

public interface TokenValidator {

    Token<UploadClaim> parseUploadToken(String tokenString);

    Token<DownloadClaim> parseDownloadToken(String tokenString);
}
