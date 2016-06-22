/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.compress;

import org.apache.lucene.store.IndexInput;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.bytes.BytesSequence;
import org.elasticsearch.common.compress.deflate.DeflateCompressor;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;

/**
 */
public class CompressorFactory {

    private static final Compressor compressor = new DeflateCompressor();

    public static boolean isCompressed(BytesReference bytes) {
        return compressor(bytes) != null;
    }

    /**
     * @deprecated we don't compress lucene indexes anymore and rely on lucene codecs
     */
    @Deprecated
    public static boolean isCompressed(IndexInput in) throws IOException {
        return compressor(in) != null;
    }

    @Nullable
    public static Compressor compressor(BytesSequence bytes) {
            if (compressor.isCompressed(bytes)) {
                // bytes should be either detected as compressed or as xcontent,
                // if we have bytes that can be either detected as compressed or
                // as a xcontent, we have a problem
                assert XContentFactory.xContentType(bytes) == null;
                return compressor;
            }

        XContentType contentType = XContentFactory.xContentType(bytes);
        if (contentType == null) {
            if (isAncient(bytes)) {
                throw new IllegalStateException("unsupported compression: index was created before v2.0.0.beta1 and wasn't upgraded?");
            }
            throw new NotXContentException("Compressor detection can only be called on some xcontent bytes or compressed xcontent bytes");
        }

        return null;
    }

    /** true if the bytes were compressed with LZF: only used before elasticsearch 2.0 */
    private static boolean isAncient(BytesSequence bytes) {
        return bytes.length() >= 3 &&
               bytes.get(0) == 'Z' &&
               bytes.get(1) == 'V' &&
               (bytes.get(2) == 0 || bytes.get(2) == 1);
    }

    /**
     * @deprecated we don't compress lucene indexes anymore and rely on lucene codecs
     */
    @Deprecated
    @Nullable
    public static Compressor compressor(IndexInput in) throws IOException {
        if (compressor.isCompressed(in)) {
            return compressor;
        }
        return null;
    }

    /**
     * Uncompress the provided data, data can be detected as compressed using {@link #isCompressed(BytesReference)}.
     */
    public static BytesReference uncompressIfNeeded(BytesReference bytes) throws IOException {
        Compressor compressor = compressor(bytes);
        BytesReference uncompressed;
        if (compressor != null) {
            uncompressed = uncompress(bytes, compressor);
        } else {
            uncompressed = bytes;
        }

        return uncompressed;
    }

    /** Decompress the provided {@link BytesReference}. */
    public static BytesReference uncompress(BytesReference bytes) throws IOException {
        Compressor compressor = compressor(bytes);
        if (compressor == null) {
            throw new NotCompressedException();
        }
        return uncompress(bytes, compressor);
    }

    private static BytesReference uncompress(BytesReference bytes, Compressor compressor) throws IOException {
        StreamInput compressed = compressor.streamInput(bytes.streamInput());
        BytesStreamOutput bStream = new BytesStreamOutput();
        Streams.copy(compressed, bStream);
        compressed.close();
        return bStream.bytes();
    }

    public static Compressor getCompressor() {
        return compressor;
    }
}
