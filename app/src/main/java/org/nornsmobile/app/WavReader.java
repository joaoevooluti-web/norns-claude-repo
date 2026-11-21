package org.nornsmobile.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/** Leitor WAV PCM usado para carregar buffers Softcut sem duplicar o arquivo inteiro na RAM. */
final class WavReader {
    private static final int MAX_BUFFER_SAMPLES = 16_777_216;

    /** Metadata-only WAV description; no sample payload is read or mapped. */
    static final class Info {
        final int channels; final long samples; final int sampleRate; final int bitsPerSample;
        Info(int channels, long samples, int sampleRate, int bitsPerSample) {
            this.channels = channels; this.samples = samples;
            this.sampleRate = sampleRate; this.bitsPerSample = bitsPerSample;
        }
    }

    static final class Data {
        final float[] samples;
        final int sampleRate;
        final int channels;
        Data(float[] samples, int sampleRate, int channels) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    static final class StereoData {
        /** Interleaved left/right PCM samples. */
        final float[] samples;
        final int sampleRate;
        StereoData(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    static final class MappedStereoData {
        final MappedByteBuffer data;
        final int sampleRate, channels, format, bits, bytesPerSample, frameBytes, frameCount;

        MappedStereoData(MappedByteBuffer data, int sampleRate, int channels,
                         int format, int bits, int frameCount) {
            this.data = data;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.format = format;
            this.bits = bits;
            this.bytesPerSample = bits / 8;
            this.frameBytes = bytesPerSample * channels;
            this.frameCount = frameCount;
        }

        float sample(int frame, int channel) {
            int sourceChannel = channels == 1 ? 0 : Math.max(0, Math.min(1, channel));
            int offset = frame * frameBytes + sourceChannel * bytesPerSample;
            double value;
            if (format == 3) value = Float.intBitsToFloat(data.getInt(offset));
            else if (bits == 16) value = data.getShort(offset) / 32768.0;
            else if (bits == 24) {
                int raw = (data.get(offset) & 255) | ((data.get(offset + 1) & 255) << 8)
                        | ((data.get(offset + 2) & 255) << 16);
                if ((raw & 0x800000) != 0) raw |= 0xff000000;
                value = raw / 8388608.0;
            } else value = data.getInt(offset) / 2147483648.0;
            if (!Double.isFinite(value)) return 0f;
            return (float)Math.max(-1, Math.min(1, value));
        }
    }


    /**
     * Maps a WAV's sample data for bounded-heap Tape playback. The file is
     * parsed and prefaulted before the audio callback reads from the mapping.
     */
    /** Reads RIFF/WAVE chunk headers only; sample payload is skipped by seeking. */
    static Info inspect(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 44)
            throw new IOException("WAV inválido ou incompleto");
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[12]; in.readFully(header);
            if (!"RIFF".equals(fourcc(header, 0)) || !"WAVE".equals(fourcc(header, 8)))
                throw new IOException("formato WAV não reconhecido");
            int format = 0, channels = 0, rate = 0, bits = 0;
            long dataSize = -1, fileLength = in.length();
            while (in.getFilePointer() + 8 <= fileLength) {
                byte[] chunk = new byte[8]; in.readFully(chunk);
                String id = fourcc(chunk, 0);
                long size = u32(chunk, 4), chunkStart = in.getFilePointer();
                long chunkEnd = chunkStart + size;
                if (chunkEnd < chunkStart || chunkEnd > fileLength)
                    throw new IOException("bloco WAV truncado");
                if ("fmt ".equals(id)) {
                    if (size < 16) throw new IOException("cabeçalho WAV inválido");
                    byte[] fmt = new byte[16]; in.readFully(fmt);
                    format = u16(fmt, 0); channels = u16(fmt, 2);
                    rate = (int) u32(fmt, 4); bits = u16(fmt, 14);
                } else if ("data".equals(id)) dataSize = size;
                in.seek(chunkEnd + (size & 1L));
            }
            if (dataSize <= 0 || channels < 1 || channels > 2
                    || rate < 8000 || rate > 384000
                    || (bits != 16 && bits != 24 && bits != 32)
                    || (format != 1 && format != 3) || (format == 3 && bits != 32))
                throw new IOException("WAV deve ser PCM 16/24/32-bit ou float 32-bit, mono/estéreo");
            int frameBytes = channels * (bits / 8);
            if (dataSize % frameBytes != 0)
                throw new IOException("dados WAV terminam no meio de um quadro");
            long frames = dataSize / frameBytes;
            if (frames <= 0) throw new IOException("WAV sem amostras");
            return new Info(channels, frames, rate, bits);
        }
    }

    static MappedStereoData mapStereo(File file) throws IOException {
        if (file == null || file.length() < 44 || file.length() > 100L * 1024 * 1024)
            throw new IOException("WAV inválido ou maior que 100 MB");
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            byte[] header = new byte[12];
            in.readFully(header);
            if (!fourcc(header, 0).equals("RIFF") || !fourcc(header, 8).equals("WAVE"))
                throw new IOException("formato WAV não reconhecido");
            int format = 0, channels = 0, rate = 0, bits = 0;
            long dataAt = -1, dataSize = 0, fileLength = in.length();
            while (in.getFilePointer() + 8 <= fileLength) {
                byte[] chunk = new byte[8];
                in.readFully(chunk);
                String id = fourcc(chunk, 0);
                long size = u32(chunk, 4);
                long chunkStart = in.getFilePointer();
                long chunkEnd = chunkStart + size;
                if (chunkEnd < chunkStart || chunkEnd > fileLength)
                    throw new IOException("bloco WAV truncado");
                if ("fmt ".equals(id)) {
                    if (size < 16) throw new IOException("cabeçalho WAV inválido");
                    byte[] fmt = new byte[16];
                    in.readFully(fmt);
                    format = u16(fmt, 0);
                    channels = u16(fmt, 2);
                    rate = (int)u32(fmt, 4);
                    bits = u16(fmt, 14);
                } else if ("data".equals(id)) {
                    dataAt = chunkStart;
                    dataSize = size;
                }
                in.seek(chunkEnd + (size & 1L));
            }
            if (dataAt < 0 || channels < 1 || channels > 2 || rate < 8000 || rate > 384000
                    || (bits != 16 && bits != 24 && bits != 32)
                    || (format != 1 && format != 3) || (format == 3 && bits != 32))
                throw new IOException("WAV deve ser PCM 16/24/32-bit ou float 32-bit, mono/estéreo");
            int frameBytes = channels * (bits / 8);
            if (dataSize < frameBytes || dataSize % frameBytes != 0 || dataSize > Integer.MAX_VALUE)
                throw new IOException("dados WAV incompletos ou fora do limite");
            int frames = (int)(dataSize / frameBytes);
            MappedByteBuffer mapped = in.getChannel().map(FileChannel.MapMode.READ_ONLY, dataAt, dataSize);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            mapped.load();
            return new MappedStereoData(mapped, rate, channels, format, bits, frames);
        } finally {
            in.close();
        }
    }

    static Data read(File file) throws IOException { return read(file, 0); }

    /**
     * Loads a mono or stereo WAV as interleaved stereo for the Tape transport.
     * Mono sources are duplicated to both output channels. The explicit memory
     * bound prevents a long file from exhausting the Android process heap.
     */
    static StereoData readStereo(File file) throws IOException {
        if (file == null || file.length() < 44 || file.length() > 100L * 1024 * 1024)
            throw new IOException("WAV inválido ou maior que 100 MB");
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            byte[] header = new byte[12];
            in.readFully(header);
            if (!fourcc(header, 0).equals("RIFF") || !fourcc(header, 8).equals("WAVE"))
                throw new IOException("formato WAV não reconhecido");

            int format = 0, channels = 0, rate = 0, bits = 0;
            long dataAt = -1, dataSize = 0, fileLength = in.length();
            while (in.getFilePointer() + 8 <= fileLength) {
                byte[] chunk = new byte[8];
                in.readFully(chunk);
                String id = fourcc(chunk, 0);
                long size = u32(chunk, 4);
                long chunkStart = in.getFilePointer();
                long chunkEnd = chunkStart + size;
                if (chunkEnd < chunkStart || chunkEnd > fileLength)
                    throw new IOException("bloco WAV truncado");
                if ("fmt ".equals(id)) {
                    if (size < 16) throw new IOException("cabeçalho WAV inválido");
                    byte[] fmt = new byte[16];
                    in.readFully(fmt);
                    format = u16(fmt, 0);
                    channels = u16(fmt, 2);
                    rate = (int)u32(fmt, 4);
                    bits = u16(fmt, 14);
                } else if ("data".equals(id)) {
                    dataAt = chunkStart;
                    dataSize = size;
                }
                in.seek(chunkEnd + (size & 1L));
            }

            if (dataAt < 0 || channels < 1 || channels > 2 || rate < 8000 || rate > 384000
                    || (bits != 16 && bits != 24 && bits != 32)
                    || (format != 1 && format != 3) || (format == 3 && bits != 32))
                throw new IOException("WAV deve ser PCM 16/24/32-bit ou float 32-bit, mono/estéreo");
            int bytes = bits / 8;
            int frameBytes = bytes * channels;
            if (dataSize % frameBytes != 0)
                throw new IOException("dados WAV terminam no meio de um quadro");
            long frameCount = dataSize / frameBytes;
            if (frameCount < 1) throw new IOException("WAV sem amostras");
            if (frameCount * 2L > MAX_BUFFER_SAMPLES)
                throw new IOException("WAV excede o limite de memória do Tape");

            float[] stereo = new float[(int)frameCount * 2];
            byte[] frame = new byte[frameBytes];
            in.seek(dataAt);
            for (int i = 0; i < (int)frameCount; i++) {
                in.readFully(frame);
                double left = decodeSample(frame, 0, format, bits);
                double right = channels == 1 ? left : decodeSample(frame, bytes, format, bits);
                if (!Double.isFinite(left)) left = 0;
                if (!Double.isFinite(right)) right = 0;
                stereo[i * 2] = (float)Math.max(-1, Math.min(1, left));
                stereo[i * 2 + 1] = (float)Math.max(-1, Math.min(1, right));
            }
            return new StereoData(stereo, rate);
        } finally {
            in.close();
        }
    }

    private static double decodeSample(byte[] frame, int offset, int format, int bits) {
        if (format == 3) return Float.intBitsToFloat(i32(frame, offset));
        if (bits == 16) return (short)u16(frame, offset) / 32768.0;
        if (bits == 24) {
            int raw = (frame[offset] & 255) | ((frame[offset + 1] & 255) << 8)
                    | ((frame[offset + 2] & 255) << 16);
            if ((raw & 0x800000) != 0) raw |= 0xff000000;
            return raw / 8388608.0;
        }
        return i32(frame, offset) / 2147483648.0;
    }

    /** sourceChannel: 0 = downmix; 1/2 = canal específico (mono aceita 1). */
    static Data read(File file, int sourceChannel) throws IOException {
        if (file == null || file.length() < 44 || file.length() > 100L * 1024 * 1024) {
            throw new IOException("WAV inválido ou maior que 100 MB");
        }
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            byte[] header = new byte[12];
            in.readFully(header);
            if (!fourcc(header, 0).equals("RIFF") || !fourcc(header, 8).equals("WAVE")) {
                throw new IOException("formato WAV não reconhecido");
            }

            int format = 0, channels = 0, rate = 0, bits = 0;
            long dataAt = -1, dataSize = 0;
            long fileLength = in.length();
            while (in.getFilePointer() + 8 <= fileLength) {
                byte[] chunk = new byte[8];
                in.readFully(chunk);
                String id = fourcc(chunk, 0);
                long size = u32(chunk, 4);
                long start = in.getFilePointer();
                long end = start + size;
                if (end < start || end > fileLength) throw new IOException("bloco WAV truncado");

                if ("fmt ".equals(id)) {
                    if (size < 16) throw new IOException("cabeçalho WAV inválido");
                    byte[] fmt = new byte[16];
                    in.readFully(fmt);
                    format = u16(fmt, 0);
                    channels = u16(fmt, 2);
                    rate = (int) u32(fmt, 4);
                    bits = u16(fmt, 14);
                } else if ("data".equals(id)) {
                    dataAt = start;
                    dataSize = size;
                }

                in.seek(end + (size & 1L));
            }

            if (dataAt < 0 || channels < 1 || channels > 2 || rate < 8000 || rate > 384000
                    || (bits != 16 && bits != 24 && bits != 32)
                    || (format != 1 && format != 3) || (format == 3 && bits != 32)) {
                throw new IOException("WAV deve ser PCM 16/24/32-bit ou float 32-bit, mono/estéreo");
            }
            if (sourceChannel < 0 || sourceChannel > channels) {
                throw new IOException("Canal de origem WAV inválido");
            }

            int bytes = bits / 8;
            int frameBytes = bytes * channels;
            if (dataSize % frameBytes != 0) throw new IOException("dados WAV terminam no meio de um quadro");
            long frameCount = dataSize / frameBytes;
            if (frameCount < 1) throw new IOException("WAV sem amostras");
            if (frameCount > MAX_BUFFER_SAMPLES) {
                throw new IOException("WAV excede o limite de 16.777.216 amostras por buffer Softcut");
            }

            int count = (int) frameCount;
            float[] out = new float[count];
            byte[] frame = new byte[bytes * channels];
            int first = sourceChannel == 0 ? 0 : sourceChannel - 1;
            int last = sourceChannel == 0 ? channels : first + 1;
            in.seek(dataAt);
            for (int i = 0; i < count; i++) {
                in.readFully(frame);
                double sum = 0;
                for (int ch = first; ch < last; ch++) {
                    int q = ch * bytes;
                    double value;
                    if (format == 3) {
                        value = Float.intBitsToFloat(i32(frame, q));
                    } else if (bits == 16) {
                        value = (short) u16(frame, q) / 32768.0;
                    } else if (bits == 24) {
                        int raw = (frame[q] & 255) | ((frame[q + 1] & 255) << 8)
                                | ((frame[q + 2] & 255) << 16);
                        if ((raw & 0x800000) != 0) raw |= 0xff000000;
                        value = raw / 8388608.0;
                    } else {
                        value = i32(frame, q) / 2147483648.0;
                    }
                    sum += value;
                }
                double mixed = sum / (last - first);
                if (Double.isNaN(mixed) || Double.isInfinite(mixed)) mixed = 0;
                out[i] = (float) Math.max(-1.0, Math.min(1.0, mixed));
            }
            return new Data(out, rate, channels);
        } finally {
            in.close();
        }
    }

    private static String fourcc(byte[] b, int p) {
        return new String(b, p, 4, java.nio.charset.Charset.forName("US-ASCII"));
    }
    private static int u16(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8);
    }
    private static long u32(byte[] b, int p) {
        return ((long) b[p] & 255) | (((long) b[p + 1] & 255) << 8)
                | (((long) b[p + 2] & 255) << 16) | (((long) b[p + 3] & 255) << 24);
    }
    private static int i32(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8)
                | ((b[p + 2] & 255) << 16) | (b[p + 3] << 24);
    }
}
