/*
 * MIT License
 *
 * Copyright (c) 2018 Rishabh Joshi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.rishabh9.riko.upstox.websockets;

import com.github.rishabh9.riko.upstox.common.Service;
import com.github.rishabh9.riko.upstox.common.UpstoxAuthService;
import com.github.rishabh9.riko.upstox.common.models.ApiCredentials;
import com.github.rishabh9.riko.upstox.common.models.UpstoxResponse;
import com.github.rishabh9.riko.upstox.login.models.AccessToken;
import com.github.rishabh9.riko.upstox.websockets.models.WebsocketParameters;
import com.github.rishabh9.riko.upstox.websockets.models.WrappedWebSocket;
import com.google.common.base.Strings;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.*;

import static com.github.rishabh9.riko.upstox.common.constants.PropertyKeys.*;

public class WebSocketService extends Service {

    private static final Logger log = LogManager.getLogger(WebSocketService.class);

    private final RetryPolicy retryPolicy;
    private final ScheduledExecutorService executorService;

    /**
     * @param upstoxAuthService The service to retrieve authentication details
     */
    public WebSocketService(@Nonnull final UpstoxAuthService upstoxAuthService) {
        super(upstoxAuthService);
        retryPolicy = new RetryPolicy()
                .retryOn(Throwable.class)
                .withBackoff(1, 180, TimeUnit.SECONDS)
                .withMaxRetries(10);
        this.executorService = Executors.newScheduledThreadPool(4);
    }

    private CompletableFuture<UpstoxResponse<WebsocketParameters>> getWebsocketParameters() {

        log.debug("Preparing service - GET Websocket Parameters");
        final WebSocketApi api = prepareServiceApi(WebSocketApi.class);

        log.debug("Making request -GET Websocket Parameters ");
        return api.getWebsocketParameters();
    }

    /**
     * Connect to Upstox via web socket.<br>
     * Internally, this method retrieves the published parameters to connect to Upstox
     * via web sockets and then tries to setup a web socket connection.
     *
     * @param subscribers The subscribers interested in the messages received at the web socket.
     *                    <em>Mandatory.</em>
     * @return The web socket wrapped in custom class for usability.
     * @throws ExecutionException   When there is an exception creating the connection.
     * @throws InterruptedException When there is an error while waiting for the connection to complete.
     */
    public WrappedWebSocket connect(final List<MessageSubscriber> subscribers)
            throws ExecutionException, InterruptedException {

        if (null == subscribers || subscribers.isEmpty()) {
            throw new IllegalArgumentException("Subscribers not provided. Not connecting to the socket.");
        }

        // Step 1: Retrieve the webSocket parameters before connecting, as per Upstox documentation.
        final UpstoxResponse<WebsocketParameters> response = Failsafe.with(retryPolicy)
                .with(executorService)
                .onFailure(failure -> {
                    log.fatal("Failed completely to retrieve web-socket parameters. ", failure);
                })
                .onSuccess(webSocketParams -> {
                    log.info("Received parameters to connect to web-socket.");
                    log.debug("Web socket connection parameters are: {}", webSocketParams);
                })
                .onRetry((c, f, ctx) ->
                        log.warn("Failure #" + ctx.getExecutions()
                                + ". Unable to retrieve web-socket parameters, retrying.", f))
                .future(this::getWebsocketParameters)
                .get();

        // Step 2: Make connection
        return Failsafe.with(retryPolicy)
                .with(executorService)
                .onFailure(failure -> log.fatal("Failed completely to make web-socket connection. ", failure))
                .onSuccess(connection -> log.info("WebSocket connection is successful!"))
                .onRetry((c, f, ctx) ->
                        log.warn("Failure #" + ctx.getExecutions() + ". Unable to connect to web-socket, retrying.", f))
                .get(() -> this.makeConnection(response.getData(), subscribers))
                .get();
    }

    private WrappedWebSocket makeConnection(final WebsocketParameters parameters,
                                            final List<MessageSubscriber> subscribers) {

        final OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder();
        final OkHttpClient httpClient = httpClientBuilder
                .connectionPool(
                        new ConnectionPool(5, parameters.getKeepaliveInterval(), TimeUnit.SECONDS))
                .readTimeout(parameters.getPythonPingTimeout() * 3, TimeUnit.SECONDS)
                .writeTimeout(parameters.getPythonPingTimeout() * 3, TimeUnit.SECONDS)
                .pingInterval(parameters.getPythonPingInterval(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(Boolean.parseBoolean(System.getProperty(RIKO_WS_RECONNECT, RIKO_WS_RECONNECT_DEFAULT)))
                .build();

        final Request request = prepareRequest();

        final WebSocket webSocket = httpClient.newWebSocket(request, new MessageListener(subscribers));

        return new WrappedWebSocket(webSocket);
    }

    private Request prepareRequest() {

        log.debug("Preparing request");

        final HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme(System.getProperty(RIKO_WS_SERVER_SCHEME, RIKO_WS_SERVER_SCHEME_DEFAULT))
                .host(System.getProperty(RIKO_WS_SERVER_URL, RIKO_WS_SERVER_URL_DEFAULT));
        final String port = System.getProperty(RIKO_WS_SERVER_PORT);
        if (!Strings.isNullOrEmpty(port)) {
            urlBuilder.port(Integer.parseInt(port));
        }
        final AccessToken accessToken = upstoxAuthService.getAccessToken();
        final ApiCredentials credentials = upstoxAuthService.getApiCredentials();
        urlBuilder
                .addQueryParameter("apiKey", credentials.getApiKey())
                .addQueryParameter("token", accessToken.getToken());

        final String token = accessToken.getType() + " " + accessToken.getToken();

        return new Request.Builder()
                .header("X-API-KEY", credentials.getApiKey())
                .header("Authorization", token)
                .header("Sec-WebSocket-Key", Double.toString(Math.random()))
                .header("Sec-WebSocket-Version", "13")
                .url(urlBuilder.build())
                .build();
    }
}
