/**
 * Copyright (c) 2018, 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hono.adapter.coap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.message.Message;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.elements.auth.ExtensiblePrincipal;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.auth.ApplicationLevelInfoSupplier;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.pskstore.PskStore;
import org.eclipse.hono.auth.Device;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.Command;
import org.eclipse.hono.client.CommandContext;
import org.eclipse.hono.client.CommandResponse;
import org.eclipse.hono.client.DownstreamSender;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.config.KeyLoader;
import org.eclipse.hono.service.AbstractProtocolAdapterBase;
import org.eclipse.hono.service.metric.MetricsTags;
import org.eclipse.hono.service.metric.MetricsTags.Direction;
import org.eclipse.hono.service.metric.MetricsTags.ProcessingOutcome;
import org.eclipse.hono.service.metric.MetricsTags.TtdStatus;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.CommandConstants;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.ResourceIdentifier;
import org.eclipse.hono.util.TenantObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import io.micrometer.core.instrument.Timer.Sample;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * Base class for a vert.x based Hono protocol adapter that uses CoAP.
 * <p>
 * Provides support for exposing Hono's southbound Telemetry &amp; Event
 * API by means of CoAP resources.
 * 
 * @param <T> The type of configuration properties used by this service.
 */
public abstract class AbstractVertxBasedCoapAdapter<T extends CoapAdapterProperties>
        extends AbstractProtocolAdapterBase<T> {

    private static final String KEY_TIMER_ID = "timerId";

    /**
     * A logger shared with subclasses.
     */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final Set<Resource> resourcesToAdd = new HashSet<>();

    /**
     * COAP server. Created from blocking execution, therefore use volatile.
     */
    private CoapServer server;
    private CoapAdapterMetrics metrics = CoapAdapterMetrics.NOOP;
    private ApplicationLevelInfoSupplier honoDeviceResolver;
    private PskStore pskStore;

    private volatile Endpoint secureEndpoint;
    private volatile Endpoint insecureEndpoint;

    /**
     * Sets the service to use for resolving an authenticated CoAP client to a Hono device.
     * 
     * @param deviceIdentityResolver The resolver to use.
     */
    public final void setHonoDeviceResolver(final ApplicationLevelInfoSupplier deviceIdentityResolver) {
        this.honoDeviceResolver = Objects.requireNonNull(deviceIdentityResolver);
    }

    /**
     * Sets the service to use for looking up pre-shared keys for clients authenticating using PSK
     * based ciphers in a DTLS handshake.
     * 
     * @param pskStore The service to use.
     */
    public final void setPskStore(final PskStore pskStore) {
        this.pskStore = Objects.requireNonNull(pskStore);
    }

    /**
     * Sets the metrics for this service.
     *
     * @param metrics The metrics
     */
    @Autowired
    public final void setMetrics(final CoapAdapterMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Gets the object for reporting this adapter's metrics.
     * 
     * @return The metrics.
     */
    protected final CoapAdapterMetrics getMetrics() {
        return metrics;
    }

    /**
     * Sets the CoAP resources that should be added to the CoAP server
     * managed by this class.
     * 
     * @param resources The resources.
     * @throws NullPointerException if resources is {@code null}.
     */
    @Autowired(required = false)
    public final void setResources(final Set<Resource> resources) {
        this.resourcesToAdd.addAll(Objects.requireNonNull(resources));
    }

    /**
     * @return {@link CoAP#DEFAULT_COAP_SECURE_PORT}
     */
    @Override
    public final int getPortDefaultValue() {
        return CoAP.DEFAULT_COAP_SECURE_PORT;
    }

    /**
     * @return {@link CoAP#DEFAULT_COAP_PORT}
     */
    @Override
    public final int getInsecurePortDefaultValue() {
        return CoAP.DEFAULT_COAP_PORT;
    }

    @Override
    protected final int getActualPort() {
        int port = Constants.PORT_UNCONFIGURED;
        final Endpoint endpoint = secureEndpoint;
        if (endpoint != null) {
            port = endpoint.getAddress().getPort();
        }
        return port;
    }

    @Override
    protected final int getActualInsecurePort() {
        int port = Constants.PORT_UNCONFIGURED;
        final Endpoint endpoint = insecureEndpoint;
        if (endpoint != null) {
            port = endpoint.getAddress().getPort();
        }
        return port;
    }

    /**
     * Sets the coap server instance configured to serve requests.
     * <p>
     * If no server is set using this method, then a server instance is created during startup of this adapter based on
     * the <em>config</em> properties.
     * 
     * @param server The coap server.
     * @throws NullPointerException if server is {@code null}.
     */
    @Autowired(required = false)
    public final void setCoapServer(final CoapServer server) {
        Objects.requireNonNull(server);
        this.server = server;
    }

    @Override
    public final void doStart(final Promise<Void> startPromise) {

        checkCoapPortConfiguration()
        .compose(s -> preStartup())
        .compose(s -> {
            final Future<NetworkConfig> secureConfig = getSecureNetworkConfig();
            final Future<NetworkConfig> insecureConfig = getInsecureNetworkConfig();

            return CompositeFuture.all(secureConfig, insecureConfig)
                    .map(ok -> {
                        final CoapServer startingServer = server == null ? new CoapServer(insecureConfig.result()) : server;
                        addResources(startingServer);
                        return startingServer;
                    })
                    .compose(server -> bindSecureEndpoint(server, secureConfig.result()))
                    .compose(server -> bindInsecureEndpoint(server, insecureConfig.result()))
                    .map(server -> {
                        server.start();
                        if (secureEndpoint != null) {
                            log.info("coaps/udp endpoint running on {}", secureEndpoint.getAddress());
                        }
                        if (insecureEndpoint != null) {
                            log.info("coap/udp endpoint running on {}", insecureEndpoint.getAddress());
                        }
                        return server;
                    });
        })
        .compose(ok -> {
            final Promise<Void> result = Promise.promise();
            try {
                onStartupSuccess();
                result.complete();
            } catch (final Exception e) {
                log.error("error in onStartupSuccess", e);
                result.fail(e);
            }
            return result.future();
        })
        .setHandler(startPromise);
    }

    private Future<Void> checkCoapPortConfiguration() {

        final Promise<Void> result = Promise.promise();

        final boolean securePortEnabled = getConfig().isSecurePortEnabled() || getConfig().getPort() > Constants.PORT_UNCONFIGURED;
        final int securePort = securePortEnabled ? getConfig().getPort(getPortDefaultValue()) : Constants.PORT_UNCONFIGURED;

        if (!securePortEnabled) {
            if (getConfig().isInsecurePortEnabled()) {
                result.complete();
            } else {
                log.error("configuration must have at least one of secure or insecure port set to start up");
                result.fail("no ports configured");
            }
        } else if (getConfig().isInsecurePortEnabled() && securePort == getConfig().getInsecurePort(getInsecurePortDefaultValue())) {
            log.error("secure and insecure ports must be configured to bind to different port numbers");
            result.fail("secure and insecure ports configured to bind to same port number");
        } else {
            if (getConfig().getKeyCertOptions() == null) {
                log.warn("secure port configured, but no certificate/key is set. Will only enable ciphers that do not require server certificate!");
            }
            result.complete();
        }

        return result.future();
    }

    private void addResources(final CoapServer startingServer) {
        resourcesToAdd.forEach(resource -> startingServer.add(new VertxCoapResource(resource, context)));
        resourcesToAdd.clear();
    }

    private void addIdentity(final DtlsConnectorConfig.Builder dtlsConfig) {

        final KeyLoader keyLoader = KeyLoader.fromFiles(vertx, getConfig().getKeyPath(), getConfig().getCertPath());
        final PrivateKey pk = keyLoader.getPrivateKey();
        final Certificate[] certChain = keyLoader.getCertificateChain();
        if (pk != null && certChain != null) {
            if (pk.getAlgorithm().equals("EC")) {
                // Californium's cipher suites support ECC based keys only
                log.info("using private key [{}] and certificate [{}] as server identity",
                        getConfig().getKeyPath(), getConfig().getCertPath());
                dtlsConfig.setIdentity(pk, certChain);
            } else {
                log.warn("configured key is not ECC based, certificate based cipher suites will be disabled");
            }
        }
    }

    private Future<CoapServer> bindSecureEndpoint(final CoapServer startingServer, final NetworkConfig config) {

        final ApplicationLevelInfoSupplier deviceResolver = Optional.ofNullable(honoDeviceResolver)
                .orElse(new DefaultDeviceResolver(context, tracer, getTypeName(), getConfig(), getCredentialsClientFactory()));
        final PskStore store = Optional.ofNullable(pskStore)
                .orElseGet(() -> {
                    if (deviceResolver instanceof PskStore) {
                        return (PskStore) deviceResolver;
                    } else {
                        return new DefaultDeviceResolver(context, tracer, getTypeName(), getConfig(), getCredentialsClientFactory());
                    }
                });

        final DtlsConnectorConfig.Builder dtlsConfig = new DtlsConnectorConfig.Builder();
        dtlsConfig.setServerOnly(true);
        dtlsConfig.setRecommendedCipherSuitesOnly(true);
        dtlsConfig.setClientAuthenticationRequired(getConfig().isAuthenticationRequired());
        dtlsConfig.setAddress(
                new InetSocketAddress(getConfig().getBindAddress(), getConfig().getPort(getPortDefaultValue())));
        dtlsConfig.setApplicationLevelInfoSupplier(deviceResolver);
        dtlsConfig.setPskStore(store);
        if (getConfig().getMaxConnections() > 0) {
            dtlsConfig.setMaxConnections(getConfig().getMaxConnections());
        }
        addIdentity(dtlsConfig);

        try {
            final DtlsConnectorConfig dtlsConnectorConfig = dtlsConfig.build();
            if (log.isInfoEnabled()) {
                final String ciphers = dtlsConnectorConfig.getSupportedCipherSuites()
                        .stream()
                        .map(cipher -> cipher.name())
                        .collect(Collectors.joining(", "));
                log.info("adding secure endpoint supporting ciphers: {}", ciphers);
            }
            final DTLSConnector dtlsConnector = new DTLSConnector(dtlsConnectorConfig);
            final CoapEndpoint.Builder builder = new CoapEndpoint.Builder();
            builder.setNetworkConfig(config);
            builder.setConnector(dtlsConnector);
            this.secureEndpoint = builder.build();
            startingServer.addEndpoint(this.secureEndpoint);
            return Future.succeededFuture(startingServer);

        } catch (final IllegalStateException ex) {
            log.warn("failed to create secure endpoint", ex);
            return Future.failedFuture(ex);
        }

    }

    private Future<CoapServer> bindInsecureEndpoint(final CoapServer startingServer, final NetworkConfig config) {

        if (getConfig().isInsecurePortEnabled()) {
            if (getConfig().isAuthenticationRequired()) {
                log.warn("skipping start up of insecure endpoint, configuration requires authentication of devices");
            } else {
                final CoapEndpoint.Builder builder = new CoapEndpoint.Builder();
                builder.setNetworkConfig(config);
                builder.setInetSocketAddress(new InetSocketAddress(
                        getConfig().getInsecurePortBindAddress(),
                        getConfig().getInsecurePort(getInsecurePortDefaultValue())));
                this.insecureEndpoint = builder.build();
                startingServer.addEndpoint(this.insecureEndpoint);
            }
        }
        return Future.succeededFuture(startingServer);
    }

    /**
     * Invoked before the coap server is started.
     * <p>
     * May be overridden by sub-classes to provide additional startup handling.
     * 
     * @return A future indicating the outcome of the operation. The start up process fails if the returned future
     *         fails.
     */
    protected Future<Void> preStartup() {

        return Future.succeededFuture();
    }

    /**
     * Invoked after this adapter has started up successfully.
     * <p>
     * May be overridden by sub-classes.
     */
    protected void onStartupSuccess() {
        // empty
    }

    private NetworkConfig newDefaultNetworkConfig() {
        final NetworkConfig networkConfig = new NetworkConfig();
        networkConfig.setInt(NetworkConfig.Keys.PROTOCOL_STAGE_THREAD_COUNT, getConfig().getCoapThreads());
        networkConfig.setInt(NetworkConfig.Keys.NETWORK_STAGE_RECEIVER_THREAD_COUNT, getConfig().getConnectorThreads());
        networkConfig.setInt(NetworkConfig.Keys.NETWORK_STAGE_SENDER_THREAD_COUNT, getConfig().getConnectorThreads());
        networkConfig.setInt(NetworkConfig.Keys.MAX_RESOURCE_BODY_SIZE, getConfig().getMaxPayloadSize());
        return networkConfig;
    }

    /**
     * Gets the CoAP network configuration for the secure endpoint.
     * <ol>
     * <li>Creates a default CoAP network configuration based on {@link CoapAdapterProperties}.</li>
     * <li>Merge in network configuration loaded from {@link CoapAdapterProperties#getNetworkConfig()}.</li>
     * <li>Merge in network configuration loaded from {@link CoapAdapterProperties#getSecureNetworkConfig()}.</li>
     * </ol>
     * 
     * @return The network configuration for the secure endpoint.
     */
    protected Future<NetworkConfig> getSecureNetworkConfig() {

        final NetworkConfig networkConfig = newDefaultNetworkConfig();
        return loadNetworkConfig(getConfig().getNetworkConfig(), networkConfig)
                .compose(c -> loadNetworkConfig(getConfig().getSecureNetworkConfig(), c));
    }

    /**
     * Gets the CoAP network configuration for the insecure endpoint.
     * <ol>
     * <li>Creates a default CoAP network configuration based on {@link CoapAdapterProperties}.</li>
     * <li>Merge in network configuration loaded from {@link CoapAdapterProperties#getNetworkConfig()}.</li>
     * <li>Merge in network configuration loaded from {@link CoapAdapterProperties#getInsecureNetworkConfig()}.</li>
     * </ol>
     * 
     * @return The network configuration for the insecure endpoint.
     */
    protected Future<NetworkConfig> getInsecureNetworkConfig() {

        final NetworkConfig networkConfig = newDefaultNetworkConfig();
        return loadNetworkConfig(getConfig().getNetworkConfig(), networkConfig)
                .compose(c -> loadNetworkConfig(getConfig().getInsecureNetworkConfig(), c));
    }

    /**
     * Loads Californium configuration properties from a file.
     * 
     * @param fileName The absolute path to the properties file.
     * @param networkConfig The configuration to apply the properties to.
     * @return The updated configuration.
     */
    protected Future<NetworkConfig> loadNetworkConfig(final String fileName, final NetworkConfig networkConfig) {

        if (fileName != null && !fileName.isEmpty()) {
            getVertx().fileSystem().readFile(fileName, readAttempt -> {
                if (readAttempt.succeeded()) {
                    try (InputStream is = new ByteArrayInputStream(readAttempt.result().getBytes())) {
                        networkConfig.load(is);
                    } catch (final IOException e) {
                        log.warn("skipping malformed NetworkConfig properties [{}]", fileName);
                    }
                } else {
                    log.warn("error reading NetworkConfig file [{}]", fileName, readAttempt.cause());
                }
            });
        }
        return Future.succeededFuture(networkConfig);
    }

    /**
     * Invoked before the message is sent to the downstream peer.
     * <p>
     * Subclasses may override this method in order to customize the message before it is sent, e.g. adding custom
     * properties.
     * <p>
     * This default implementation does nothing.
     * 
     * @param downstreamMessage The message that will be sent downstream.
     * @param context The context representing the request to be processed.
     */
    protected void customizeDownstreamMessage(final Message downstreamMessage, final CoapContext context) {
    }

    @Override
    public final void doStop(final Promise<Void> stopPromise) {

        try {
            preShutdown();
        } catch (final Exception e) {
            log.error("error in preShutdown", e);
        }

        final Promise<Void> serverStopTracker = Promise.promise();
        if (server != null) {
            getVertx().executeBlocking(future -> {
                // Call some blocking API
                server.stop();
                future.complete();
            }, serverStopTracker);
        } else {
            serverStopTracker.complete();
        }

        serverStopTracker.future()
        .compose(v -> postShutdown())
        .setHandler(stopPromise);
    }

    /**
     * Invoked before the coap server is shut down. May be overridden by sub-classes.
     */
    protected void preShutdown() {
        // empty
    }

    /**
     * Invoked after the Adapter has been shutdown successfully. May be overridden by sub-classes to provide further
     * shutdown handling.
     * 
     * @return A future that has to be completed when this operation is finished.
     */
    protected Future<Void> postShutdown() {
        return Future.succeededFuture();
    }

    /**
     * Gets an authenticated device's identity for a CoAP request.
     * 
     * @param device The device that the data in the request payload originates from.
     *               If {@code null}, the origin of the data is assumed to be the authenticated device.
     * @param exchange The CoAP exchange with the authenticated device's principal.
     * @return A future indicating the outcome of the operation.
     *         The future will be succeeded if the authenticated device can be determined from the CoAP exchange,
     *         otherwise the future will be failed with a {@link ClientErrorException}.
     */
    protected final Future<ExtendedDevice> getAuthenticatedExtendedDevice(
            final Device device,
            final CoapExchange exchange) {

        final Promise<ExtendedDevice> result = Promise.promise();
        final Principal peerIdentity = exchange.advanced().getRequest().getSourceContext().getPeerIdentity();
        if (peerIdentity instanceof ExtensiblePrincipal) {
            @SuppressWarnings("unchecked")
            final ExtensiblePrincipal<? extends Principal> extPrincipal = (ExtensiblePrincipal<? extends Principal>) peerIdentity;
            final Device authenticatedDevice = extPrincipal.getExtendedInfo().get("hono-device", Device.class);
            if (authenticatedDevice != null) {
                final Device originDevice = Optional.ofNullable(device).orElse(authenticatedDevice);
                result.complete(new ExtendedDevice(authenticatedDevice, originDevice));
            } else {
                result.fail(new ClientErrorException(
                        HttpURLConnection.HTTP_UNAUTHORIZED,
                        "DTLS session does not contain authenticated Device"));
            }
        } else {
            result.fail(new ClientErrorException(
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    "DTLS session does not contain ExtensiblePrincipal"));

        }

        return result.future();
    }

    /**
     * Forwards the body of a CoAP request to the south bound Telemetry API of the AMQP 1.0 Messaging Network.
     * 
     * @param context The context representing the request to be processed.
     * @param authenticatedDevice authenticated device
     * @param originDevice message's origin device
     * @return A future containing the response code that has been returned to
     *         the device.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    public final Future<ResponseCode> uploadTelemetryMessage(
            final CoapContext context,
            final Device authenticatedDevice,
            final Device originDevice) {

        return doUploadMessage(
                Objects.requireNonNull(context),
                Objects.requireNonNull(authenticatedDevice),
                Objects.requireNonNull(originDevice),
                context.isConfirmable(),
                getTelemetrySender(authenticatedDevice.getTenantId()),
                MetricsTags.EndpointType.TELEMETRY);
    }

    /**
     * Forwards the body of a CoAP request to the south bound Event API of the AMQP 1.0 Messaging Network.
     * 
     * @param context The context representing the request to be processed.
     * @param authenticatedDevice authenticated device
     * @param originDevice message's origin device
     * @return A future containing the response code that has been returned to
     *         the device.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    public final Future<ResponseCode> uploadEventMessage(final CoapContext context, final Device authenticatedDevice,
            final Device originDevice) {

        Objects.requireNonNull(context);
        if (context.isConfirmable()) {
            return doUploadMessage(
                    Objects.requireNonNull(context),
                    Objects.requireNonNull(authenticatedDevice),
                    Objects.requireNonNull(originDevice),
                    true,
                    getEventSender(authenticatedDevice.getTenantId()),
                    MetricsTags.EndpointType.EVENT);
        } else {
            context.respondWithCode(ResponseCode.BAD_REQUEST, "event endpoint supports confirmable request messages only");
            return Future.succeededFuture(ResponseCode.BAD_REQUEST);
        }
    }

    /**
     * Forwards a message to the south bound Telemetry or Event API of the AMQP 1.0 Messaging Network.
     * <p>
     * Depending on the outcome of the attempt to upload the message, the CoAP response code is set as
     * described by the <a href="https://www.eclipse.org/hono/docs/user-guide/coap-adapter/">CoAP adapter user guide</a>
     * 
     * @param context The context representing the request to be processed.
     * @param authenticatedDevice authenticated device
     * @param device message's origin device
     * @param waitForOutcome {@code true} to send the message waiting for the outcome, {@code false}, to wait just for
     *            the sent.
     * @param senderTracker hono message sender tracker
     * @param endpoint message destination endpoint
     * @return A succeeded future containing the CoAP status code that has been returned to the device.
     */
    private Future<ResponseCode> doUploadMessage(
            final CoapContext context,
            final Device authenticatedDevice,
            final Device device,
            final boolean waitForOutcome,
            final Future<DownstreamSender> senderTracker,
            final MetricsTags.EndpointType endpoint) {

        final String contentType = context.getContentType();
        final Buffer payload = context.getPayload();

        if (contentType == null) {
            context.respondWithCode(ResponseCode.BAD_REQUEST, "request message must contain content-format option");
            return Future.succeededFuture(ResponseCode.BAD_REQUEST);
        } else if (payload.length() == 0 && !context.isEmptyNotification()) {
            context.respondWithCode(ResponseCode.BAD_REQUEST, "request contains no body but is not marked as empty notification");
            return Future.succeededFuture(ResponseCode.BAD_REQUEST);
        } else {
            final String gatewayId = authenticatedDevice != null
                    && !device.getDeviceId().equals(authenticatedDevice.getDeviceId())
                            ? authenticatedDevice.getDeviceId()
                            : null;

            final Span currentSpan = TracingHelper
                    .buildChildSpan(tracer, context.getTracingContext(),
                            "upload " + endpoint.getCanonicalName(), getTypeName())
                    .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                    .withTag(MessageHelper.APP_PROPERTY_TENANT_ID, device.getTenantId())
                    .withTag(MessageHelper.APP_PROPERTY_DEVICE_ID, device.getDeviceId())
                    .withTag(TracingHelper.TAG_AUTHENTICATED.getKey(), authenticatedDevice != null)
                    .start();

            final Promise<Void> responseReady = Promise.promise();

            final Future<JsonObject> tokenTracker = getRegistrationAssertion(
                    device.getTenantId(), device.getDeviceId(),
                    authenticatedDevice,
                    currentSpan.context());
            final Future<TenantObject> tenantTracker = getTenantConfiguration(device.getTenantId(),
                    currentSpan.context());
            final Future<TenantObject> tenantValidationTracker = tenantTracker
                    .compose(tenantObject -> CompositeFuture
                            .all(isAdapterEnabled(tenantObject),
                                    checkMessageLimit(tenantObject, payload.length(), currentSpan.context()))
                            .map(success -> tenantObject));

            // we only need to consider TTD if the device and tenant are enabled and the adapter
            // is enabled for the tenant
            final Future<Integer> ttdTracker = CompositeFuture.all(tenantValidationTracker, tokenTracker)
                    .compose(ok -> {
                        final Integer ttdParam = context.getTimeUntilDisconnect();
                        return getTimeUntilDisconnect(tenantTracker.result(), ttdParam).map(effectiveTtd -> {
                            if (effectiveTtd != null) {
                                currentSpan.setTag(MessageHelper.APP_PROPERTY_DEVICE_TTD, effectiveTtd);
                            }
                            return effectiveTtd;
                        });
                    });
            final Future<MessageConsumer> commandConsumerTracker = ttdTracker
                    .compose(ttd -> createCommandConsumer(
                            ttd,
                            tenantTracker.result(),
                            device.getDeviceId(),
                            gatewayId,
                            context,
                            responseReady,
                            currentSpan));

            return CompositeFuture.join(senderTracker, commandConsumerTracker).compose(ok -> {
                final DownstreamSender sender = senderTracker.result();
                final Integer ttd = ttdTracker.result();
                final Message downstreamMessage = newMessage(
                        ResourceIdentifier.from(endpoint.getCanonicalName(), device.getTenantId(),
                                device.getDeviceId()),
                        "/" + context.getExchange().getRequestOptions().getUriPathString(),
                        contentType,
                        payload,
                        tenantValidationTracker.result(),
                        tokenTracker.result(),
                        ttd);
                customizeDownstreamMessage(downstreamMessage, context);

                if (waitForOutcome) {
                    // wait for outcome, ensure message order, if CoAP NSTART-1 is used.
                    return CompositeFuture.all(
                            sender.sendAndWaitForOutcome(downstreamMessage, currentSpan.context()),
                            responseReady.future())
                            .mapEmpty();
                } else {
                    return CompositeFuture.all(
                            sender.send(downstreamMessage, currentSpan.context()),
                            responseReady.future())
                            .mapEmpty();
                }
            }).compose(ok -> {

                final CommandContext commandContext = context.get(CommandContext.KEY_COMMAND_CONTEXT);
                final Response response = new Response(ResponseCode.CHANGED);
                if (commandContext == null) {

                    if (ttdTracker.result() != null) {
                        if (responseReady.future().isComplete()) {
                            log.debug("==>> no command, ready!");
                        } else {
                            log.warn("==>> no command, failed: {}!", responseReady.future().cause());
                        }
                    }

                } else {

                    addCommandToResponse(response, commandContext, currentSpan);
                    commandContext.accept();
                    metrics.reportCommand(
                            commandContext.getCommand().isOneWay() ? Direction.ONE_WAY : Direction.REQUEST,
                            device.getTenantId(),
                            tenantTracker.result(),
                            ProcessingOutcome.FORWARDED,
                            commandContext.getCommand().getPayloadSize(),
                            context.getTimer());
                }

                log.trace("successfully processed message for device [tenantId: {}, deviceId: {}, endpoint: {}]",
                        device.getTenantId(), device.getDeviceId(), endpoint.getCanonicalName());
                metrics.reportTelemetry(
                        endpoint,
                        device.getTenantId(),
                        tenantTracker.result(),
                        MetricsTags.ProcessingOutcome.FORWARDED,
                        waitForOutcome ? MetricsTags.QoS.AT_LEAST_ONCE : MetricsTags.QoS.AT_MOST_ONCE,
                        payload.length(),
                        getTtdStatus(context),
                        context.getTimer());
                currentSpan.finish();
                final Promise<ResponseCode> result = Promise.promise();
                final Handler<AsyncResult<Void>> closed = res -> {
                    context.getExchange().respond(response);
                    result.complete(response.getCode());
                    };
                Optional.ofNullable(commandConsumerTracker.result()).ifPresentOrElse(
                        consumer -> consumer.close(closed),
                        () -> closed.handle(Future.succeededFuture()));
                return result.future();

            }).otherwise(t -> {

                log.debug("cannot process message for device [tenantId: {}, deviceId: {}, endpoint: {}]",
                        device.getTenantId(), device.getDeviceId(), endpoint.getCanonicalName(), t);
                metrics.reportTelemetry(
                        endpoint,
                        device.getTenantId(),
                        tenantTracker.result(),
                        ClientErrorException.class.isInstance(t) ? MetricsTags.ProcessingOutcome.UNPROCESSABLE : MetricsTags.ProcessingOutcome.UNDELIVERABLE,
                        waitForOutcome ? MetricsTags.QoS.AT_LEAST_ONCE : MetricsTags.QoS.AT_MOST_ONCE,
                        payload.length(),
                        getTtdStatus(context),
                        context.getTimer());
                TracingHelper.logError(currentSpan, t);
                currentSpan.finish();
                Optional.ofNullable(commandConsumerTracker.result()).ifPresent(consumer -> consumer.close(null));
                return CoapErrorResponse.respond(context.getExchange(), t);
            });
        }
    }

    /**
     * Adds a command to a CoAP response.
     * <p>
     * This default implementation adds the command name, content format and response URI to the
     * CoAP response options and puts the command's input data (if any) to the response body.
     *
     * @param response The CoAP response.
     * @param commandContext The context containing the command to add.
     * @param currentSpan The Open Tracing span used for tracking the CoAP request.
     */
    protected void addCommandToResponse(
            final Response response,
            final CommandContext commandContext,
            final Span currentSpan) {

        final Command command = commandContext.getCommand();
        final OptionSet options = response.getOptions();
        options.addLocationQuery(Constants.HEADER_COMMAND + "=" + command.getName());
        if (command.isOneWay()) {
            options.setLocationPath(CommandConstants.COMMAND_ENDPOINT);
        } else {
            options.setLocationPath(CommandConstants.COMMAND_RESPONSE_ENDPOINT);
        }

        currentSpan.setTag(Constants.HEADER_COMMAND, command.getName());
        log.debug("adding command [name: {}, request-id: {}] to response for device [tenant-id: {}, device-id: {}]",
                command.getName(), command.getRequestId(), command.getTenant(), command.getDeviceId());
        commandContext.getCurrentSpan().log("forwarding command to device in CoAP response");

        if (command.isTargetedAtGateway()) {
            options.addLocationPath(command.getTenant());
            options.addLocationPath(command.getOriginalDeviceId());
            currentSpan.setTag(Constants.HEADER_COMMAND_TARGET_DEVICE, command.getOriginalDeviceId());
        }
        if (!command.isOneWay()) {
            options.addLocationPath(command.getRequestId());
            currentSpan.setTag(Constants.HEADER_COMMAND_REQUEST_ID, command.getRequestId());
        }
        final int formatCode = MediaTypeRegistry.parse(command.getContentType());
        if (formatCode != MediaTypeRegistry.UNDEFINED) {
            options.setContentFormat(formatCode);
        } else {
            currentSpan.log("ignoring unknown content type [" + command.getContentType() + "] of command");
        }
        Optional.ofNullable(command.getPayload()).ifPresent(b -> response.setPayload(b.getBytes()));
    }

    /**
     * Creates a consumer for command messages to be sent to a device.
     *
     * @param ttdSecs The number of seconds the device waits for a command.
     * @param tenantObject The tenant configuration object.
     * @param deviceId The identifier of the device.
     * @param gatewayId The identifier of the gateway that is acting on behalf of the device or {@code null} otherwise.
     * @param context The device's currently executing CoAP request context.
     * @param responseReady A future to complete once one of the following conditions are met:
     *            <ul>
     *            <li>the request did not include a <em>hono-ttd</em> query-parameter or</li>
     *            <li>a command has been received and the response ready future has not yet been completed or</li>
     *            <li>the ttd has expired</li>
     *            </ul>
     * @param currentSpan The OpenTracing Span to use for tracking the processing of the request.
     * @return A future indicating the outcome of the operation.
     *         <p>
     *         The future will be completed with the created message consumer or {@code null}, if the response can be
     *         sent back to the device without waiting for a command.
     *         <p>
     *         The future will be failed with a {@code ServiceInvocationException} if the message consumer could not be
     *         created.
     * @throws NullPointerException if any of the parameters other than TTD or gatewayId is {@code null}.
     */
    protected final Future<MessageConsumer> createCommandConsumer(
            final Integer ttdSecs,
            final TenantObject tenantObject,
            final String deviceId,
            final String gatewayId,
            final CoapContext context,
            final Handler<AsyncResult<Void>> responseReady,
            final Span currentSpan) {

        Objects.requireNonNull(tenantObject);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(context);
        Objects.requireNonNull(responseReady);
        Objects.requireNonNull(currentSpan);

        final AtomicBoolean requestProcessed = new AtomicBoolean(false);

        if (ttdSecs == null || ttdSecs <= 0) {
            // no need to wait for a command
            if (requestProcessed.compareAndSet(false, true)) {
                responseReady.handle(Future.succeededFuture());
            }
            return Future.succeededFuture();
        }

        final Handler<CommandContext> commandHandler = commandContext -> {

            Tags.COMPONENT.set(commandContext.getCurrentSpan(), getTypeName());
            final Command command = commandContext.getCommand();
            final Sample commandSample = getMetrics().startTimer();
            if (isCommandValid(command, currentSpan)) {

                if (requestProcessed.compareAndSet(false, true)) {
                    checkMessageLimit(tenantObject, command.getPayloadSize(), currentSpan.context())
                            .setHandler(result -> {
                                if (result.succeeded()) {
                                    addMicrometerSample(commandContext, commandSample);
                                    // put command context to routing context and notify
                                    context.put(CommandContext.KEY_COMMAND_CONTEXT, commandContext);
                                } else {
                                    commandContext.reject(getErrorCondition(result.cause()));
                                    metrics.reportCommand(
                                            command.isOneWay() ? Direction.ONE_WAY : Direction.REQUEST,
                                            tenantObject.getTenantId(),
                                            tenantObject,
                                            ProcessingOutcome.from(result.cause()),
                                            command.getPayloadSize(),
                                            commandSample);
                                }
                                cancelCommandReceptionTimer(context);
                                setTtdStatus(context, TtdStatus.COMMAND);
                                responseReady.handle(Future.succeededFuture());
                            });
                } else {
                    // the timer has already fired, release the command
                    getMetrics().reportCommand(
                            command.isOneWay() ? Direction.ONE_WAY : Direction.REQUEST,
                            tenantObject.getTenantId(),
                            tenantObject,
                            ProcessingOutcome.UNDELIVERABLE,
                            command.getPayloadSize(),
                            commandSample);
                    log.debug("command for device has already fired [tenantId: {}, deviceId: {}]",
                            tenantObject.getTenantId(), deviceId);
                    commandContext.release();
                }

            } else {
                getMetrics().reportCommand(
                        command.isOneWay() ? Direction.ONE_WAY : Direction.REQUEST,
                        tenantObject.getTenantId(),
                        tenantObject,
                        ProcessingOutcome.UNPROCESSABLE,
                        command.getPayloadSize(),
                        commandSample);
                log.debug("command message is invalid: {}", command);
                commandContext.reject(new ErrorCondition(Constants.AMQP_BAD_REQUEST, "malformed command message"));
            }
            // we do not issue any new credit because the
            // consumer is supposed to deliver a single command
            // only per HTTP request
        };

        final Future<MessageConsumer> commandConsumerFuture;
        if (gatewayId != null) {
            // gateway scenario
            commandConsumerFuture = getCommandConsumerFactory().createCommandConsumer(
                    tenantObject.getTenantId(),
                    deviceId,
                    gatewayId,
                    commandHandler,
                    currentSpan.context());
        } else {
            commandConsumerFuture = getCommandConsumerFactory().createCommandConsumer(
                    tenantObject.getTenantId(),
                    deviceId,
                    commandHandler,
                    currentSpan.context());
        }
        return commandConsumerFuture
                .map(consumer -> {
                    if (!requestProcessed.get()) {
                        // if the request was not responded already, add a timer for triggering an empty response
                        addCommandReceptionTimer(context, requestProcessed, responseReady, ttdSecs);
                        context.getExchange().accept();
                    }
                    return consumer;
                });
    }

    /**
     * Validate if a command is valid and can be sent as response.
     * <p>
     * The default implementation will call {@link Command#isValid()}. Protocol adapters may override this, but should
     * consider calling the super method.
     *
     * @param command The command to validate, will never be {@code null}.
     * @param currentSpan The current tracing span.
     * @return {@code true} if the command is valid, {@code false} otherwise.
     */
    protected boolean isCommandValid(final Command command, final Span currentSpan) {
        return command.isValid();
    }

    /**
     * Sets a timer to trigger the sending of a (empty) response to a device if no command has been received from an
     * application within a given amount of time.
     * <p>
     * The created timer's ID is put to the routing context using key {@link #KEY_TIMER_ID}.
     *
     * @param context The device's currently executing HTTP request.
     * @param requestProcessed protect request from multiple responses
     * @param responseReady The future to complete when the time has expired.
     * @param delaySecs The number of seconds to wait for a command.
     */
    private void addCommandReceptionTimer(
            final CoapContext context,
            final AtomicBoolean requestProcessed,
            final Handler<AsyncResult<Void>> responseReady,
            final long delaySecs) {

        final Long timerId = vertx.setTimer(delaySecs * 1000L, id -> {

            log.trace("time to wait [{}s] for command expired [timer id: {}]", delaySecs, id);

            if (requestProcessed.compareAndSet(false, true)) {
                // no command to be sent,
                // send empty response
                setTtdStatus(context, TtdStatus.EXPIRED);
                responseReady.handle(Future.succeededFuture());
            } else {
                // a command has been sent to the device already
                log.trace("response already sent, nothing to do ...");
            }
        });

        log.trace("adding command reception timer [id: {}]", timerId);

        context.put(KEY_TIMER_ID, timerId);
    }

    private void cancelCommandReceptionTimer(final CoapContext context) {

        final Long timerId = context.get(KEY_TIMER_ID);
        if (timerId != null && timerId >= 0) {
            if (vertx.cancelTimer(timerId)) {
                log.trace("Cancelled timer id {}", timerId);
            } else {
                log.debug("Could not cancel timer id {}", timerId);
            }
        }
    }

    private void setTtdStatus(final CoapContext context, final TtdStatus status) {
        context.put(TtdStatus.class.getName(), status);
    }

    private TtdStatus getTtdStatus(final CoapContext context) {
        return Optional.ofNullable((TtdStatus) context.get(TtdStatus.class.getName()))
                .orElse(TtdStatus.NONE);
    }

    /**
     * Uploads a command response message to Hono.
     *
     * @param context The context representing the request to be processed.
     * @param authenticatedDevice The device that has sent the request. This might be different from the
     *                            device that has executed the command, e.g. a gateway.
     * @param device The device that has executed the command.
     * @return A succeeded future containing the CoAP status code that has been returned to the device.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    public final Future<ResponseCode> uploadCommandResponseMessage(
            final CoapContext context,
            final Device authenticatedDevice,
            final Device device) {

        Objects.requireNonNull(context);
        Objects.requireNonNull(authenticatedDevice);
        Objects.requireNonNull(device);

        if (!context.isConfirmable()) {
            context.respondWithCode(ResponseCode.BAD_REQUEST, "command response endpoint supports confirmable request messages only");
            return Future.succeededFuture(ResponseCode.BAD_REQUEST);
        }

        final Buffer payload = context.getPayload();
        final String contentType = context.getContentType();
        final String commandRequestId = context.getCommandRequestId();
        final Integer responseStatus = context.getCommandResponseStatus();
        log.debug("processing response to command [tenantId: {}, deviceId: {}, cmd-req-id: {}, status code: {}]",
                device.getTenantId(), device.getDeviceId(), commandRequestId, responseStatus);

        final Span currentSpan = TracingHelper
                .buildChildSpan(tracer, context.getTracingContext(), "upload Command response", getTypeName())
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .withTag(MessageHelper.APP_PROPERTY_TENANT_ID, device.getTenantId())
                .withTag(MessageHelper.APP_PROPERTY_DEVICE_ID, device.getDeviceId())
                .withTag(Constants.HEADER_COMMAND_RESPONSE_STATUS, responseStatus)
                .withTag(Constants.HEADER_COMMAND_REQUEST_ID, commandRequestId)
                .withTag(TracingHelper.TAG_AUTHENTICATED.getKey(), authenticatedDevice != null)
                .start();

        final Future<TenantObject> tenantTracker = getTenantConfiguration(device.getTenantId(), currentSpan.context());
        final Optional<CommandResponse> cmdResponse = Optional.ofNullable(CommandResponse.from(
                commandRequestId,
                device.getTenantId(),
                device.getDeviceId(),
                payload,
                contentType,
                responseStatus));
        final Future<CommandResponse> commandResponseTracker = cmdResponse
                .map(res -> Future.succeededFuture(res))
                .orElse(Future.failedFuture(
                        new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                        String.format("command-request-id [%s] or status code [%s] is missing/invalid",
                                commandRequestId, responseStatus))));

        return CompositeFuture.all(tenantTracker, commandResponseTracker)
                .compose(ok -> {
                    final Future<JsonObject> deviceRegistrationTracker = getRegistrationAssertion(
                            device.getTenantId(),
                            device.getDeviceId(),
                            authenticatedDevice,
                            currentSpan.context());
                    final Future<Void> tenantValidationTracker = CompositeFuture.all(
                            isAdapterEnabled(tenantTracker.result()),
                            checkMessageLimit(tenantTracker.result(), payload.length(), currentSpan.context()))
                            .mapEmpty();

                    return CompositeFuture.all(tenantValidationTracker, deviceRegistrationTracker);
                })
                .compose(ok -> sendCommandResponse(device.getTenantId(), commandResponseTracker.result(), currentSpan.context()))
                .map(delivery -> {
                    log.trace("delivered command response [command-request-id: {}] to application",
                            commandRequestId);
                    currentSpan.log("delivered command response to application");
                    currentSpan.finish();
                    metrics.reportCommand(
                            Direction.RESPONSE,
                            device.getTenantId(),
                            tenantTracker.result(),
                            ProcessingOutcome.FORWARDED,
                            payload.length(),
                            context.getTimer());
                    context.respondWithCode(ResponseCode.CHANGED);
                    return ResponseCode.CHANGED;
                })
                .otherwise(t -> {
                    log.debug("could not send command response [command-request-id: {}] to application",
                            commandRequestId, t);
                    TracingHelper.logError(currentSpan, t);
                    currentSpan.finish();
                    metrics.reportCommand(
                            Direction.RESPONSE,
                            device.getTenantId(),
                            tenantTracker.result(),
                            ProcessingOutcome.from(t),
                            payload.length(),
                            context.getTimer());
                    return CoapErrorResponse.respond(context.getExchange(), t);
                });
    }
}
