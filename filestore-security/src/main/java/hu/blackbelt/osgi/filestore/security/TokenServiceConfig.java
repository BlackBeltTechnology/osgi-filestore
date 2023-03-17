package hu.blackbelt.osgi.filestore.security;

/*-
 * #%L
 * JUDO framework security for filestore
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

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
