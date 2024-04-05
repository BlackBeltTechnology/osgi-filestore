package hu.blackbelt.osgi.filestore.api;

import lombok.NonNull;

public class FilenameUtils {
    @NonNull
    public static String makeValidFilename(String fileName) {
        String fn = fileName.trim()
            .replaceAll("^[aA][uU][xX]$|^[cC][lL][oO][cC][kK]$|^[cC][oO][nN]$|^[nN][uU][lL]$|^[pP][rR][nN]$|^[cC][oO][mM][1-9]$|^[lL][pP][tT][1-9]$", "reserved")
            .replaceAll("[$()+=\\[\\];#@~,&']", "")
            .replaceAll("[ ][.]+", ".")
            .replaceAll("[.][ ]+", ".")
            .replaceAll("\\.+", ".")
            .replaceAll("\\s+", " ")
            .trim()
            .replaceAll("[.]$", "");

        if (fn.length() > 128) {
            return fn.substring(0, 127);
        }
        return fn;
    }

    public static void main(String[] args) {
        System.out.println(makeValidFilename("Hdshddas .....         asxasd...     .dd.... "));
    }
}
