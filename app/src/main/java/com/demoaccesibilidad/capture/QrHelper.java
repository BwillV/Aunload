package com.demoaccesibilidad.capture;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.HashMap;
import java.util.Map;

/**
 * Genera códigos QR localmente con ZXing (sin Google Play Services).
 * El QR se dibuja a mano en un Bitmap, por lo que no depende de GMS.
 */
public final class QrHelper {

    private QrHelper() {}

    /**
     * Devuelve un Bitmap con el código QR del contenido dado.
     * @param contenido texto/URL a codificar.
     * @param size tamaño en px (cuadrado).
     * @return Bitmap o null si falla.
     */
    public static Bitmap generar(String contenido, int size) {
        if (contenido == null || contenido.isEmpty() || size <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matrix = new MultiFormatWriter()
                    .encode(contenido, BarcodeFormat.QR_CODE, size, size, hints);

            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
