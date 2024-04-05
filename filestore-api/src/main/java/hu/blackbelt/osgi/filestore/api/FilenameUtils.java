package hu.blackbelt.osgi.filestore.api;

import lombok.NonNull;

public class FilenameUtils {
    @NonNull
    public static String makeValidFilename(String fileName) {
        String fn = fileName.trim();
        while (fn.contains("  ")) {
            fn = fn.replaceAll("  ", " ");
        }
        while (fn.contains("..")) {
            fn = fn.replaceAll("\\.\\.", ".");
        }
        while (fn.endsWith(".")) {
            fn = fn.replaceAll("[.]$", "");
        }
        fn = fn.trim();

        fn = fn.replaceAll("^[aA][uU][xX]$|^[cC][lL][oO][cC][kK]$|^[cC][oO][nN]$|^[nN][uU][lL]$|^[pP][rR][nN]$|^[cC][oO][mM][1-9]$|^[lL][pP][tT][1-9]$", "reserved")
                .replaceAll("[$()+=\\[\\];#@~,&']", "");
        if (fn.length() > 128) {
            return fn.substring(0, 127);
        }
        return fn;
    }
}
