package hu.blackbelt.osgi.filestore.mime.impl;

import hu.blackbelt.osgi.filestore.mime.api.MimeTypeResolver;
import org.apache.sling.commons.mime.MimeTypeService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.util.*;
import java.util.regex.Pattern;

@Component(immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = DefaultMimeTypeResolver.Config.class)
public class DefaultMimeTypeResolver implements MimeTypeResolver {

    @ObjectClassDefinition()
    public @interface Config {

        @AttributeDefinition(required = false, name = "Default MIME type")
        String defaultMimeType() default "application/pdf";

        @AttributeDefinition(required = false, name = "MIME type by URL")
        String mimeTypeByUrl() default ".*/documentDownload.*type\\=PDF=application/pdf";
    }

    public static final String ESCAPED_EQUAL_STRING_ORIGINAL = "\\\\=";
    public static final String ESCAPED_EQUAL_STRING_REPLACEMENT = Character.toString((char) '\u2202');
    public static final String ESCAPED_SEMICOLON_STRING_ORIGINAL = "\\\\;";
    public static final String ESCAPED_SEMICOLON_STRING_REPLACEMENT = Character.toString((char) '\u2203');

    public static final String EQUAL = "=";
    public static final String SEMICOLON = ";";

    @Reference
    MimeTypeService mimeTypeService;

    private Map<Pattern, String> mimeTypeByUrlRegex;
    private String defaultMimeType;

    @Activate
    protected void activate(Config config) {
        defaultMimeType = config.defaultMimeType();

        mimeTypeByUrlRegex = parseMimeTypeMap(config.mimeTypeByUrl());
        defaultMimeType = config.defaultMimeType();
    }

    @Override
    public String getMimeType(String url) {

        // First check the URL march. When there
        for (Pattern p : mimeTypeByUrlRegex.keySet()) {
            if (p.matcher(url).find()) {
                return mimeTypeByUrlRegex.get(p);
            }
        }

        String mimeType = mimeTypeService.getMimeType(url);
        if (mimeType == null) {
            return defaultMimeType;
        } else {
            return mimeType;
        }
    }

    @Override
    public String getExtension(String mimeType) {
        return mimeTypeService.getExtension(mimeType);
    }

    public Map<Pattern, String> parseMimeTypeMap(String parse) {
        String[] splitted = parse.replaceAll(ESCAPED_EQUAL_STRING_ORIGINAL, ESCAPED_EQUAL_STRING_REPLACEMENT)
                .replaceAll(ESCAPED_SEMICOLON_STRING_ORIGINAL, ESCAPED_SEMICOLON_STRING_REPLACEMENT)
                .split("\\s*" + SEMICOLON + "\\s*");
        Map<String, String> encoded = new TreeMap<>();
        for (String s : splitted) {
            String[] keyValuePair = s.split("\\s*" + EQUAL + "\\s*");
            encoded.put(keyValuePair[0], keyValuePair[1]);
        }

        Map<Pattern, String> ret = new HashMap<>();
        for (Map.Entry<String, String> e : encoded.entrySet()) {
            ret.put(Pattern.compile(
                    e.getKey().replaceAll(ESCAPED_EQUAL_STRING_REPLACEMENT, EQUAL).replaceAll(ESCAPED_SEMICOLON_STRING_ORIGINAL, SEMICOLON)),
                    e.getValue().replaceAll(ESCAPED_EQUAL_STRING_REPLACEMENT, EQUAL).replaceAll(ESCAPED_SEMICOLON_STRING_ORIGINAL, SEMICOLON));
        }
        return Collections.unmodifiableMap(ret);
    }
}
