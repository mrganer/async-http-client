/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.jdk;

import com.ning.http.client.Cookie;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.util.AsyncHttpProviderUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


public class JDKResponse implements Response {
    private final static String DEFAULT_CHARSET = "ISO-8859-1";        
    private final static String HEADERS_NOT_COMPUTED = "Response's headers hasn't been computed by your AsyncHandler.";
    private final static String BODY_NOT_COMPUTED = "Response's body hasn't been computed by your AsyncHandler.";

    private final URI uri;
    private final Collection<HttpResponseBodyPart> bodyParts;
    private final HttpResponseHeaders headers;
    private final HttpResponseStatus status;
    private final List<Cookie> cookies = new ArrayList<Cookie>();
    private AtomicBoolean contentComputed = new AtomicBoolean(false);
    private String content;

    public JDKResponse(HttpResponseStatus status,
                       HttpResponseHeaders headers,
                       Collection<HttpResponseBodyPart> bodyParts) {

        this.bodyParts = bodyParts;
        this.headers = headers;
        this.status = status;

        uri = this.status.getUrl();
    }

    /* @Override */

    public int getStatusCode() {
        return status.getStatusCode();
    }

    /* @Override */

    public String getStatusText() {
        return status.getStatusText();
    }

    /* @Override */

    public String getResponseBody() throws IOException {
        return getResponseBody(DEFAULT_CHARSET);
    }

    public String getResponseBody(String charset) throws IOException {
        String contentType = getContentType();
        if (contentType != null) {
            charset = AsyncHttpProviderUtils.parseCharset(contentType);
        }

        if (charset == null) {
            charset = DEFAULT_CHARSET;
        }

        if (!contentComputed.get()) {
            contentToString(charset);
        }
        return content;
    }

    String contentToString(String charset) throws UnsupportedEncodingException {
        checkBodyParts();

        StringBuilder b = new StringBuilder();
        for (HttpResponseBodyPart bp : bodyParts) {
            b.append(new String(bp.getBodyPartBytes(), charset));
        }
        content = b.toString();
        return content;
    }

    /* @Override */

    public InputStream getResponseBodyAsStream() throws IOException {
        checkBodyParts();

        if (contentComputed.get()) {
            return new ByteArrayInputStream(content.getBytes(DEFAULT_CHARSET));
        }

        return new ByteArrayCollectionInputStream(bodyParts.toArray(new HttpResponseBodyPart[bodyParts.size()]));
    }

    private static class ByteArrayCollectionInputStream extends InputStream {

        private final HttpResponseBodyPart[] parts;

        private int currentPos = 0;
        private int bytePos = -1;
        private byte[] active;
        private int available = 0;

        public ByteArrayCollectionInputStream(HttpResponseBodyPart[] parts) {
            this.parts = parts;
            active = parts[0].getBodyPartBytes();
            computeLength(parts);
        }

        private void computeLength(HttpResponseBodyPart[] parts){
            if (available == 0) {
                for (HttpResponseBodyPart p : parts) {
                    available += p.getBodyPartBytes().length;
                }
            }
        }

        @Override
        public int available() throws IOException {
            return available;
        }

        @Override
        public int read() throws IOException {
            if (++bytePos >= active.length) {
                // No more bytes, so step to the next array.
                if (++currentPos >= parts.length) {
                    return -1;
                }

                bytePos = 0;
                active = parts[currentPos].getBodyPartBytes();
            }

            return active[bytePos];
        }
    }

    private void checkBodyParts() {
        if (bodyParts == null || bodyParts.size() == 0) {
            throw new IllegalStateException(BODY_NOT_COMPUTED);
        }
    }

    /* @Override */

    public String getResponseBodyExcerpt(int maxLength) throws IOException {
        return getResponseBodyExcerpt(maxLength, DEFAULT_CHARSET);
    }

    public String getResponseBodyExcerpt(int maxLength, String charset) throws IOException {
        String contentType = getContentType();
        if (contentType != null) {
            charset = AsyncHttpProviderUtils.parseCharset(contentType);
        }

        if (charset == null) {
            charset = DEFAULT_CHARSET;
        }

        if (!contentComputed.get()) {
            contentToString(charset == null ? DEFAULT_CHARSET : charset);
        }

        return content.length() <= maxLength ? content : content.substring(0, maxLength);
    }

    /* @Override */

    public URI getUri() throws MalformedURLException {
        return uri;
    }

    /* @Override */

    public String getContentType() {
        if (headers == null) {
            throw new IllegalStateException(HEADERS_NOT_COMPUTED);
        }
        return headers.getHeaders().getFirstValue("Content-Type");
    }

    /* @Override */

    public String getHeader(String name) {
        if (headers == null) {
            throw new IllegalStateException();
        }
        return headers.getHeaders().getFirstValue(name);
    }

    /* @Override */

    public List<String> getHeaders(String name) {
        if (headers == null) {
            throw new IllegalStateException(HEADERS_NOT_COMPUTED);
        }
        return headers.getHeaders().get(name);
    }

    /* @Override */

    public FluentCaseInsensitiveStringsMap getHeaders() {
        if (headers == null) {
            throw new IllegalStateException(HEADERS_NOT_COMPUTED);
        }
        return headers.getHeaders();
    }

    /* @Override */

    public boolean isRedirected() {
        return (status.getStatusCode() >= 300) && (status.getStatusCode() <= 399);
    }

    /* @Override */

    public List<Cookie> getCookies() {
        if (headers == null) {
            throw new IllegalStateException(HEADERS_NOT_COMPUTED);
        }
        if (cookies.isEmpty()) {
            for (Map.Entry<String, List<String>> header : headers.getHeaders().entrySet()) {
                if (header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    // TODO: ask for parsed header
                    for (String value : header.getValue()) {
                        String[] fields = value.split(";\\s*");
                        String[] cookie = fields[0].split("=");
                        String cookieName = cookie[0];
                        String cookieValue = cookie[1];
                        String expires = "-1";
                        String path = null;
                        String domain = null;
                        boolean secure = false; // Parse each field
                        for (int j = 1; j < fields.length; j++) {
                            if ("secure".equalsIgnoreCase(fields[j])) {
                                secure = true;
                            } else if (fields[j].indexOf('=') > 0) {
                                String[] f = fields[j].split("=");
                                if ("expires".equalsIgnoreCase(f[0])) {
                                    expires = f[1];
                                } else if ("domain".equalsIgnoreCase(f[0])) {
                                    domain = f[1];
                                } else if ("path".equalsIgnoreCase(f[0])) {
                                    path = f[1];
                                }
                            }
                        }
                        cookies.add(new Cookie(domain, cookieName, cookieValue, path, Integer.valueOf(expires), secure));
                    }
                }
            }
        }
        return Collections.unmodifiableList(cookies);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseStatus() {
        return (bodyParts != null ? true : false);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseHeaders() {
        return (headers != null ? true : false);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public boolean hasResponseBody() {
        return (bodyParts != null && bodyParts.size() > 0 ? true : false);
    }
}
