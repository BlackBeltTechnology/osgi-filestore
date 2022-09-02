package hu.blackbelt.osgi.filestore.security;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition()
public @interface KeyProiderConfig {

    @AttributeDefinition(name = "JWT signature algorithm")
    String algorithm() default "HS512";

    @AttributeDefinition(required = false, name = "BASE64 coded secret for HMAC key")
    String secret();

    @AttributeDefinition(required = false, name = "BASE64 coded JSON of private and public RSA/EC key pair")
    String keys();
}
