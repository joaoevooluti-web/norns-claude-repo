package org.nornsmobile.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Escrita WAV PCM 16-bit little-endian para exportação dos buffers Softcut. */
final class WavWriter {
    private WavWriter() {}

    static void write(File file, float[] samples, int sampleRate, int channels) throws IOException {
        if (file == null || samples == null) throw new IOException("Destino ou buffer inválido");
        if (sampleRate < 8000 || sampleRate > 384000) throw new IOException("Taxa de amostragem WAV inválida");
        if (channels != 1 && channels != 2) throw new IOException("WAV aceita 1 ou 2 canais");
        if (samples.length % channels != 0) throw new IOException("Dados de áudio incompletos");
        long dataBytes = (long)samples.length * 2L;
        long byteRate = (long)sampleRate * channels * 2L;
        if (dataBytes > 0xffffffffL - 36L || byteRate > 0xffffffffL)
            throw new IOException("Buffer excede o limite WAV PCM");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IOException("Não foi possível criar a pasta de destino");

        File temp = new File(file.getPath() + ".tmp");
        File backup = new File(file.getPath() + ".bak");
        if (temp.exists() && !temp.delete()) throw new IOException("Não foi possível limpar arquivo temporário");
        if (backup.exists() && !backup.delete()) throw new IOException("Não foi possível limpar backup anterior");

        OutputStream out = new FileOutputStream(temp);
        boolean complete = false;
        try {
            ascii(out, "RIFF"); le32(out, 36L + dataBytes); ascii(out, "WAVE");
            ascii(out, "fmt "); le32(out, 16); le16(out, 1); le16(out, channels);
            le32(out, sampleRate); le32(out, byteRate);
            le16(out, channels * 2); le16(out, 16);
            ascii(out, "data"); le32(out, dataBytes);
            byte[] block = new byte[8192];
            int used = 0;
            for (float sample : samples) {
                double v = Float.isFinite(sample) ? Math.max(-1.0, Math.min(1.0, sample)) : 0.0;
                int pcm = (int)Math.round(v < 0 ? v * 32768.0 : v * 32767.0);
                block[used++] = (byte)(pcm & 255);
                block[used++] = (byte)((pcm >>> 8) & 255);
                if (used == block.length) { out.write(block, 0, used); used = 0; }
            }
            if (used > 0) out.write(block, 0, used);
            out.flush();
            complete = true;
        } finally {
            Dust.closeQuietly(out);
            if (!complete) temp.delete();
        }

        boolean backedUp = false;
        if (file.exists()) {
            if (!file.renameTo(backup)) {
                temp.delete();
                throw new IOException("Não foi possível preservar o WAV anterior");
            }
            backedUp = true;
        }
        if (!temp.renameTo(file)) {
            if (backedUp && !backup.renameTo(file))
                throw new IOException("Falha ao finalizar WAV e restaurar o arquivo anterior: " + backup.getPath());
            temp.delete();
            throw new IOException("Não foi possível finalizar o arquivo WAV; o anterior foi preservado");
        }
        if (backedUp && backup.exists() && !backup.delete())
            AppLog.i("WAV salvo; backup antigo permaneceu em " + backup.getPath());
    }

    private static void ascii(OutputStream out, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) out.write((byte)value.charAt(i));
    }
    private static void le16(OutputStream out, int value) throws IOException {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }
    private static void le32(OutputStream out, long value) throws IOException {
        out.write((int)value & 255); out.write((int)(value >>> 8) & 255);
        out.write((int)(value >>> 16) & 255); out.write((int)(value >>> 24) & 255);
    }
}