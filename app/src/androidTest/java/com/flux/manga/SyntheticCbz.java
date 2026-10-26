package com.flux.manga;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SyntheticCbz {
    private static byte[] image(Bitmap.CompressFormat format, int color) {
        Bitmap bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(format, 90, out); bitmap.recycle(); return out.toByteArray();
    }
    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTime(0);
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
    public static byte[] valid() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            add(zip, "chapter10/page1.webp", image(Bitmap.CompressFormat.WEBP_LOSSLESS, Color.GREEN));
            add(zip, "chapter2/page10.png", image(Bitmap.CompressFormat.PNG, Color.BLUE));
            add(zip, "chapter2/page02.jpg", image(Bitmap.CompressFormat.JPEG, Color.RED));
            add(zip, "chapter2/page2.png", image(Bitmap.CompressFormat.PNG, Color.BLUE));
            add(zip, "chapter2/page1.png", image(Bitmap.CompressFormat.PNG, Color.BLUE));
            add(zip, "ComicInfo.xml", "<ComicInfo/>".getBytes(StandardCharsets.UTF_8));
            add(zip, "__MACOSX/._page1.png", new byte[]{0});
        }
        return out.toByteArray();
    }
}
