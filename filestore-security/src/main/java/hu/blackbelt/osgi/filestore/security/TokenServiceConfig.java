package hu.blackbelt.osgi.filestore.security;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition()
public @interface TokenServiceConfig {

    @AttributeDefinition(name = "JWT signature algorithm")
    String algorithm() default "HS512";

    @AttributeDefinition(required = false, name = "Issuer(s)", description = "Comma-separated list of issuer(s)")
    String issuer();

    @AttributeDefinition(required = false, name = "Audience prefix")
    String audiencePrefix();

    @AttributeDefinition(required = false, name = "Expiration time", description = "Token expiration time (minutes), 0 if not expiring", type = AttributeType.INTEGER)
    int expirationTime() default 0;
}
