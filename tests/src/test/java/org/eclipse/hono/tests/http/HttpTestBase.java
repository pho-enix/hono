/**
 * Copyright (c) 2016, 2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 *
 */

package org.eclipse.hono.tests.http;

import java.net.HttpURLConnection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.tests.CrudHttpClient;
import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.TenantConstants;
import org.eclipse.hono.util.TenantObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;

/**
 * Base class for HTTP adapter integration tests.
 *
 */
public abstract class HttpTestBase {

    private static final Vertx VERTX = Vertx.vertx();
    private static final long  TEST_TIMEOUT = 15000; // ms

    /**
     * A client for connecting to Hono Messaging.
     */
    protected static CrudHttpClient httpClient;
    /**
     * A helper accessing the AMQP 1.0 Messaging Network and
     * for managing tenants/devices/credentials.
     */
    protected static IntegrationTestSupport helper;

    /**
     * Time out each test after five seconds.
     */
    @Rule
    public final Timeout timeout = Timeout.millis(TEST_TIMEOUT);

    /**
     * A logger to be used by subclasses.
     */
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    /**
     * Sets up clients.
     * 
     * @param ctx The vert.x test context.
     */
    @BeforeClass
    public static void init(final TestContext ctx) {

        helper = new IntegrationTestSupport(VERTX);
        helper.init(ctx);

        httpClient = new CrudHttpClient(
                VERTX,
                IntegrationTestSupport.HTTP_HOST,
                IntegrationTestSupport.HTTP_PORT);
    }

    /**
     * Deletes all temporary objects from the Device Registry which
     * have been created during the last test execution.
     * 
     * @param ctx The vert.x context.
     */
    @After
    public void deleteObjects(final TestContext ctx) {

        helper.deleteObjects(ctx);
    }

    /**
     * Closes the AMQP 1.0 Messaging Network client.
     * 
     * @param ctx The vert.x test context.
     */
    @AfterClass
    public static void disconnect(final TestContext ctx) {

        helper.disconnect(ctx);
    }

    /**
     * Sends a message on behalf of a device to the HTTP adapter.
     * 
     * @param tenantId The tenant that the device belongs to.
     * @param deviceId The identifier of the device.
     * @param payload The message to send.
     * @return A future indicating the outcome of the operation.
     *         The future will succeed if the message has been accepted
     *         by the HTTP adapter.
     *         Otherwise the future will fail with a {@link ServiceInvocationException}.
     */
    protected abstract Future<Void> send(final String tenantId, final String deviceId, final Buffer payload);

    /**
     * Creates a test specific message consumer.
     *
     * @param tenantId        The tenant to create the consumer for.
     * @param messageConsumer The handler to invoke for every message received.
     * @return A future succeeding with the created consumer.
     */
    protected abstract Future<MessageConsumer> createConsumer(final String tenantId, final Consumer<Message> messageConsumer);

    /**
     * Verifies that a number of messages uploaded to Hono's HTTP adapter can be successfully
     * consumed via the AMQP Messaging Network.
     * 
     * @param ctx The test context.
     * @throws InterruptedException if the test fails.
     */
    @Test
    public void testUploadMessages(final TestContext ctx) throws InterruptedException {

        final int messagesToSend = 100;
        final CountDownLatch received = new CountDownLatch(messagesToSend);
        final Async setup = ctx.async();
        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final TenantObject tenant = TenantObject.from(tenantId, true);

        helper.registry.addTenant(JsonObject.mapFrom(tenant))
            .compose(ok -> helper.registry.registerDevice(tenantId, deviceId))
            .compose(ok -> createConsumer(tenantId, msg -> {
                LOGGER.trace("received {}", msg);
                assertMessageProperties(ctx, msg);
                assertAdditionalMessageProperties(ctx, msg);
                received.countDown();
                if (received.getCount() % 20 == 0) {
                    LOGGER.info("messages received: {}", messagesToSend - received.getCount());
                }
            })).setHandler(ctx.asyncAssertSuccess(ok -> setup.complete()));

        setup.await();

        final long start = System.currentTimeMillis();
        final AtomicInteger messageCount = new AtomicInteger(0);

        while (messageCount.get() < messagesToSend) {

            final Async sending = ctx.async();
            send(tenantId, deviceId, Buffer.buffer("hello " + messageCount.getAndIncrement())).setHandler(attempt -> {
                if (attempt.succeeded()) {
                    LOGGER.debug("sent message {} [status code: 202]", messageCount.get());
                } else {
                    LOGGER.debug("sent message {} [status code: {}]", messageCount.get(),
                            ((ServiceInvocationException) attempt.cause()).getErrorCode());
                }
                sending.complete();
            });

            if (messageCount.get() % 20 == 0) {
                LOGGER.info("messages sent: " + messageCount.get());
            }
            sending.await();
        }

        long timeToWait = Math.max(TEST_TIMEOUT - 1000, Math.round(messagesToSend * 1.2));
        if (!received.await(timeToWait, TimeUnit.MILLISECONDS)) {
            LOGGER.info("sent {} and received {} messages after {} milliseconds",
                    messageCount, messagesToSend - received.getCount(), System.currentTimeMillis() - start);
            ctx.fail("did not receive all messages sent");
        } else {
            LOGGER.info("sent {} and received {} messages after {} milliseconds",
                    messageCount, messagesToSend - received.getCount(), System.currentTimeMillis() - start);
        }
    }

    /**
     * Verifies that the HTTP adapter rejects messages from a device
     * that belongs to a tenant for which the HTTP adapter has been disabled.
     *
     * @param ctx The test context
     */
    @Test
    public void testUploadMessageFailsForDisabledTenant(final TestContext ctx) {

        // GIVEN a tenant for which the HTTP adapter is disabled
        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final JsonObject adapterDetailsHttp = new JsonObject()
                .put(TenantConstants.FIELD_ADAPTERS_TYPE, Constants.PROTOCOL_ADAPTER_TYPE_HTTP)
                .put(TenantConstants.FIELD_ENABLED, Boolean.FALSE);
        final TenantObject tenant = TenantObject.from(tenantId, true);
        tenant.addAdapterConfiguration(adapterDetailsHttp);

        final Async setup = ctx.async();
        helper.registry.addTenant(JsonObject.mapFrom(tenant))
            .compose(ok -> helper.registry.registerDevice(tenantId, deviceId))
            .setHandler(ctx.asyncAssertSuccess(ok -> setup.complete()));
        setup.await();

        // WHEN a device that belongs to the tenant uploads a message
        send(tenantId, deviceId, Buffer.buffer("hello")).setHandler(ctx.asyncAssertFailure(t -> {
            // THEN the message gets rejected by the HTTP adapter
            ctx.assertEquals(HttpURLConnection.HTTP_FORBIDDEN, ((ServiceInvocationException) t).getErrorCode());
        }));
    }

    private void assertMessageProperties(final TestContext ctx, final Message msg) {
        ctx.assertNotNull(MessageHelper.getDeviceId(msg));
        ctx.assertNotNull(MessageHelper.getTenantIdAnnotation(msg));
        ctx.assertNotNull(MessageHelper.getDeviceIdAnnotation(msg));
        ctx.assertNull(MessageHelper.getRegistrationAssertion(msg));
    }

    /**
     * Perform additional checks on a received message.
     * <p>
     * This default implementation does nothing. Subclasses should override this method to implement
     * reasonable checks.
     * 
     * @param ctx The test context.
     * @param msg The message to perform checks on.
     */
    protected void assertAdditionalMessageProperties(final TestContext ctx, final Message msg) {
        // empty
    }
}
