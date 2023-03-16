/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.http.cts;

import static android.net.http.cts.util.TestUtilsKt.assertOKStatusCode;
import static android.net.http.cts.util.TestUtilsKt.skipIfNoInternetConnection;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.HttpException;
import android.net.http.InlineExecutionProhibitedException;
import android.net.http.UploadDataProvider;
import android.net.http.UploadDataSink;
import android.net.http.UrlRequest;
import android.net.http.UrlRequest.Status;
import android.net.http.UrlResponseInfo;
import android.net.http.cts.util.HttpCtsTestServer;
import android.net.http.cts.util.TestStatusListener;
import android.net.http.cts.util.TestUploadDataProvider;
import android.net.http.cts.util.TestUrlRequestCallback;
import android.net.http.cts.util.TestUrlRequestCallback.ResponseStep;
import android.net.http.cts.util.UploadDataProviders;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.base.Strings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class UrlRequestTest {
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private TestUrlRequestCallback mCallback;
    private HttpCtsTestServer mTestServer;
    private HttpEngine mHttpEngine;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        skipIfNoInternetConnection(context);
        HttpEngine.Builder builder = new HttpEngine.Builder(context);
        mHttpEngine = builder.build();
        mCallback = new TestUrlRequestCallback();
        mTestServer = new HttpCtsTestServer(context);
    }

    @After
    public void tearDown() throws Exception {
        if (mHttpEngine != null) {
            mHttpEngine.shutdown();
        }
        if (mTestServer != null) {
            mTestServer.shutdown();
        }
    }

    private UrlRequest.Builder createUrlRequestBuilder(String url) {
        return mHttpEngine.newUrlRequestBuilder(url, mCallback.getExecutor(), mCallback);
    }

    @Test
    public void testUrlRequestGet_CompletesSuccessfully() throws Exception {
        String url = mTestServer.getSuccessUrl();
        UrlRequest request = createUrlRequestBuilder(url).build();
        request.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertThat("Received byte count must be > 0", info.getReceivedByteCount(), greaterThan(0L));
    }

    @Test
    public void testUrlRequestStatus_InvalidBeforeRequestStarts() throws Exception {
        UrlRequest request = createUrlRequestBuilder(mTestServer.getSuccessUrl()).build();
        // Calling before request is started should give Status.INVALID,
        // since the native adapter is not created.
        TestStatusListener statusListener = new TestStatusListener();
        request.getStatus(statusListener);
        statusListener.expectStatus(Status.INVALID);
    }

    @Test
    public void testUrlRequestCancel_CancelCalled() throws Exception {
        UrlRequest request = createUrlRequestBuilder(mTestServer.getSuccessUrl()).build();
        mCallback.setAutoAdvance(false);

        request.start();
        mCallback.waitForNextStep();
        assertSame(mCallback.mResponseStep, ResponseStep.ON_RESPONSE_STARTED);

        request.cancel();
        mCallback.expectCallback(ResponseStep.ON_CANCELED);
    }

    @Test
    public void testUrlRequestPost_EchoRequestBody() throws Exception {
        String testData = "test";
        UrlRequest.Builder builder = createUrlRequestBuilder(mTestServer.getEchoBodyUrl());

        TestUploadDataProvider dataProvider =
                new TestUploadDataProvider(
                        TestUploadDataProvider.SuccessCallbackMode.SYNC, mCallback.getExecutor());
        dataProvider.addRead(testData.getBytes());
        builder.setUploadDataProvider(dataProvider, mCallback.getExecutor());
        builder.addHeader("Content-Type", "text/html");
        builder.build().start();
        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);

        assertOKStatusCode(mCallback.mResponseInfo);
        assertEquals(testData, mCallback.mResponseAsString);
        dataProvider.assertClosed();
    }

    @Test
    public void testUrlRequestFail_FailedCalled() {
        createUrlRequestBuilder("http://0.0.0.0:0/").build().start();
        mCallback.expectCallback(ResponseStep.ON_FAILED);
    }

    @Test
    public void testUrlRequest_directExecutor_allowed() throws InterruptedException {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        callback.setAllowDirectExecutor(true);
        UrlRequest.Builder builder = mHttpEngine.newUrlRequestBuilder(
                mTestServer.getEchoBodyUrl(), DIRECT_EXECUTOR, callback);
        UploadDataProvider dataProvider = InMemoryUploadDataProvider.fromUtf8String("test");
        builder.setUploadDataProvider(dataProvider, DIRECT_EXECUTOR);
        builder.addHeader("Content-Type", "text/plain;charset=UTF-8");
        builder.setDirectExecutorAllowed(true);
        builder.build().start();
        callback.blockForDone();

        if (callback.mOnErrorCalled) {
            throw new AssertionError("Expected no exception", callback.mError);
        }

        assertEquals(200, callback.mResponseInfo.getHttpStatusCode());
        assertEquals("test", callback.mResponseAsString);
    }

    @Test
    public void testUrlRequest_directExecutor_disallowed_uploadDataProvider() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        // This applies just locally to the test callback, not to SUT
        callback.setAllowDirectExecutor(true);

        UrlRequest.Builder builder = mHttpEngine.newUrlRequestBuilder(
                mTestServer.getEchoBodyUrl(), Executors.newSingleThreadExecutor(), callback);
        UploadDataProvider dataProvider = InMemoryUploadDataProvider.fromUtf8String("test");

        builder.setUploadDataProvider(dataProvider, DIRECT_EXECUTOR)
                .addHeader("Content-Type", "text/plain;charset=UTF-8")
                .build()
                .start();
        callback.blockForDone();

        assertTrue(callback.mOnErrorCalled);
        assertTrue(callback.mError.getCause() instanceof InlineExecutionProhibitedException);
    }

    @Test
    public void testUrlRequest_directExecutor_disallowed_responseCallback() throws Exception {
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        // This applies just locally to the test callback, not to SUT
        callback.setAllowDirectExecutor(true);

        UrlRequest.Builder builder = mHttpEngine.newUrlRequestBuilder(
                mTestServer.getEchoBodyUrl(), DIRECT_EXECUTOR, callback);
        UploadDataProvider dataProvider = InMemoryUploadDataProvider.fromUtf8String("test");

        builder.setUploadDataProvider(dataProvider, Executors.newSingleThreadExecutor())
                .addHeader("Content-Type", "text/plain;charset=UTF-8")
                .build()
                .start();
        callback.blockForDone();

        assertTrue(callback.mOnErrorCalled);
        assertTrue(callback.mError.getCause() instanceof InlineExecutionProhibitedException);
    }

    @Test
    public void testUrlRequest_nonDirectByteBuffer() throws Exception {
        BlockingQueue<HttpException> onFailedException = new ArrayBlockingQueue<>(1);

        UrlRequest request =
                mHttpEngine
                        .newUrlRequestBuilder(
                                mTestServer.getSuccessUrl(),
                                Executors.newSingleThreadExecutor(),
                                new StubUrlRequestCallback() {
                                    @Override
                                    public void onResponseStarted(
                                            UrlRequest request, UrlResponseInfo info) {
                                        // note: allocate, not allocateDirect
                                        request.read(ByteBuffer.allocate(1024));
                                    }

                                    @Override
                                    public void onFailed(
                                            UrlRequest request,
                                            UrlResponseInfo info,
                                            HttpException error) {
                                        onFailedException.add(error);
                                    }
                                })
                        .build();
        request.start();

        HttpException e = onFailedException.poll(5, TimeUnit.SECONDS);
        assertNotNull(e);
        assertTrue(e.getCause() instanceof IllegalArgumentException);
        assertTrue(e.getCause().getMessage().contains("direct"));
    }

    @Test
    public void testUrlRequest_fullByteBuffer() throws Exception {
        BlockingQueue<HttpException> onFailedException = new ArrayBlockingQueue<>(1);

        UrlRequest request =
                mHttpEngine
                        .newUrlRequestBuilder(
                                mTestServer.getSuccessUrl(),
                                Executors.newSingleThreadExecutor(),
                                new StubUrlRequestCallback() {
                                    @Override
                                    public void onResponseStarted(
                                            UrlRequest request, UrlResponseInfo info) {
                                        ByteBuffer bb = ByteBuffer.allocateDirect(1024);
                                        bb.position(bb.limit());
                                        request.read(bb);
                                    }

                                    @Override
                                    public void onFailed(
                                            UrlRequest request,
                                            UrlResponseInfo info,
                                            HttpException error) {
                                        onFailedException.add(error);
                                    }
                                })
                        .build();
        request.start();

        HttpException e = onFailedException.poll(5, TimeUnit.SECONDS);
        assertNotNull(e);
        assertTrue(e.getCause() instanceof IllegalArgumentException);
        assertTrue(e.getCause().getMessage().contains("full"));
    }

    @Test
    public void testUrlRequest_redirects() throws Exception {
        int expectedNumRedirects = 5;
        String url =
                mTestServer.getRedirectingAssetUrl("html/hello_world.html", expectedNumRedirects);

        UrlRequest request = createUrlRequestBuilder(url).build();
        request.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertThat(mCallback.mResponseAsString).contains("hello world");
        assertThat(info.getUrlChain()).hasSize(expectedNumRedirects + 1);
        assertThat(info.getUrlChain().get(0)).isEqualTo(url);
        assertThat(info.getUrlChain().get(expectedNumRedirects)).isEqualTo(info.getUrl());
    }

    @Test
    public void testUrlRequestPost_withRedirect() throws Exception {
        String body = Strings.repeat(
                "Hello, this is a really interesting body, so write this 100 times.", 100);

        String redirectUrlParameter =
                URLEncoder.encode(mTestServer.getEchoBodyUrl(), "UTF-8");
        createUrlRequestBuilder(
                String.format(
                        "%s/alt_redirect?dest=%s&statusCode=307",
                        mTestServer.getBaseUri(),
                        redirectUrlParameter))
                .setHttpMethod("POST")
                .addHeader("Content-Type", "text/plain")
                .setUploadDataProvider(
                        UploadDataProviders.create(body.getBytes(StandardCharsets.UTF_8)),
                        mCallback.getExecutor())
                .build()
                .start();
        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);

        assertOKStatusCode(mCallback.mResponseInfo);
        assertThat(mCallback.mResponseAsString).isEqualTo(body);
    }

    private static class StubUrlRequestCallback implements UrlRequest.Callback {

        @Override
        public void onRedirectReceived(
                UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onReadCompleted(
                UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, HttpException error) {
            throw new UnsupportedOperationException(error);
        }

        @Override
        public void onCanceled(@NonNull UrlRequest request, @Nullable UrlResponseInfo info) {
            throw new UnsupportedOperationException();
        }
    }

    private static class InMemoryUploadDataProvider extends UploadDataProvider {
        private final byte[] mBody;
        private int mNextChunkStartIndex = 0;

        private InMemoryUploadDataProvider(byte[] body) {
            this.mBody = body;
        }

        static InMemoryUploadDataProvider fromUtf8String(String body) {
            return new InMemoryUploadDataProvider(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public long getLength() {
            return mBody.length;
        }

        @Override
        public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) {
            if (mNextChunkStartIndex >= getLength()) {
                throw new IllegalStateException("Body of known length is exhausted");
            }
            int nextChunkSize =
                    Math.min(mBody.length - mNextChunkStartIndex, byteBuffer.remaining());
            byteBuffer.put(mBody, mNextChunkStartIndex, nextChunkSize);
            mNextChunkStartIndex += nextChunkSize;
            uploadDataSink.onReadSucceeded(false);
        }

        @Override
        public void rewind(UploadDataSink uploadDataSink) {
            mNextChunkStartIndex = 0;
        }
    }
}
