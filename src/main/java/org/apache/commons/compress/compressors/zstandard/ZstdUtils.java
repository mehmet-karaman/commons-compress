/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.compress.compressors.zstandard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.compress.utils.OsgiUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.CountingOutputStream;

/**
 * Utility code for the Zstandard compression format.
 *
 * @ThreadSafe
 * @since 1.16
 */
public class ZstdUtils {

    private static final class InnerNotClosingOutputStream extends CountingOutputStream {

        /**
         * The close of this class wont close its delegated output stream.
         *
         * @param delegate the output stream to which the output will be delegated
         */
        private InnerNotClosingOutputStream(OutputStream delegate) {
            super(delegate);
        }

        @Override
        public void close() throws IOException {
            // Don't close the inner output stream.
        }
    }

    enum CachedAvailability {
        DONT_CACHE, CACHED_AVAILABLE, CACHED_UNAVAILABLE
    }

    /**
     * Zstandard Frame Magic Bytes.
     */
    private static final byte[] ZSTANDARD_FRAME_MAGIC = { (byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD };

    /**
     * Skippable Frame Magic Bytes - the three common bytes.
     */
    private static final byte[] SKIPPABLE_FRAME_MAGIC = { (byte) 0x2A, (byte) 0x4D, (byte) 0x18 };

    private static volatile CachedAvailability cachedZstdAvailability;

    static {
        cachedZstdAvailability = CachedAvailability.DONT_CACHE;
        setCacheZstdAvailablity(!OsgiUtils.isRunningInOsgiEnvironment());
    }

    // only exists to support unit tests
    static CachedAvailability getCachedZstdAvailability() {
        return cachedZstdAvailability;
    }

    private static boolean internalIsZstdCompressionAvailable() {
        try {
            Class.forName("com.github.luben.zstd.ZstdInputStream");
            return true;
        } catch (final NoClassDefFoundError | Exception error) { // NOSONAR
            return false;
        }
    }

    /**
     * Are the classes required to support Zstandard compression available?
     *
     * @return true if the classes required to support Zstandard compression are available
     */
    public static boolean isZstdCompressionAvailable() {
        final CachedAvailability cachedResult = cachedZstdAvailability;
        if (cachedResult != CachedAvailability.DONT_CACHE) {
            return cachedResult == CachedAvailability.CACHED_AVAILABLE;
        }
        return internalIsZstdCompressionAvailable();
    }

    /**
     * Checks if the signature matches what is expected for a Zstandard file.
     *
     * @param signature the bytes to check
     * @param length    the number of bytes to check
     * @return true if signature matches the Ztstandard or skippable frame magic bytes, false otherwise
     */
    public static boolean matches(final byte[] signature, final int length) {
        if (length < ZSTANDARD_FRAME_MAGIC.length) {
            return false;
        }

        boolean isZstandard = true;
        for (int i = 0; i < ZSTANDARD_FRAME_MAGIC.length; ++i) {
            if (signature[i] != ZSTANDARD_FRAME_MAGIC[i]) {
                isZstandard = false;
                break;
            }
        }
        if (isZstandard) {
            return true;
        }

        if (0x50 == (signature[0] & 0xF0)) {
            // skippable frame
            for (int i = 0; i < SKIPPABLE_FRAME_MAGIC.length; ++i) {
                if (signature[i + 1] != SKIPPABLE_FRAME_MAGIC[i]) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    /**
     * Whether to cache the result of the Zstandard for Java check.
     *
     * <p>
     * This defaults to {@code false} in an OSGi environment and {@code true} otherwise.
     * </p>
     *
     * @param doCache whether to cache the result
     */
    public static void setCacheZstdAvailablity(final boolean doCache) {
        if (!doCache) {
            cachedZstdAvailability = CachedAvailability.DONT_CACHE;
        } else if (cachedZstdAvailability == CachedAvailability.DONT_CACHE) {
            final boolean hasZstd = internalIsZstdCompressionAvailable();
            cachedZstdAvailability = hasZstd ? CachedAvailability.CACHED_AVAILABLE : CachedAvailability.CACHED_UNAVAILABLE;
        }
    }

    /** Private constructor to prevent instantiation of this utility class. */
    private ZstdUtils() {
    }

    /**
     * Reads uncompressed data stream and writes it compressed to the output
     *
     * @param input the data stream with uncompressed data
     * @param output the data stream for compressed output
     * @return the compressed size
     * @throws IOException throws the exception which could be got from from IOUtils.copyLarge()
     *         or ZstdCompressorOutputStream constructor
     */
    public static long readAndCompressWrite(InputStream input, OutputStream output) throws IOException {
        final InnerNotClosingOutputStream outStream = new InnerNotClosingOutputStream(output);
        final ZstdCompressorOutputStream outputStream = new ZstdCompressorOutputStream(outStream, 3, true);
        IOUtils.copyLarge(input, outputStream);
        outputStream.flush();
        return outStream.getByteCount();
    }
}
