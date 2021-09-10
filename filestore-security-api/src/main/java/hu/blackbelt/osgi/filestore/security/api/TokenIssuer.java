package hu.blackbelt.osgi.filestore.security.api;

public interface TokenIssuer {

    String createUploadToken(Token<UploadClaim> token);

    String createDownloadToken(Token<DownloadClaim> token);
}
