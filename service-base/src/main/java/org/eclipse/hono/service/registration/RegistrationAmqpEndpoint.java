/**
 * Copyright (c) 2016, 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.service.registration;

import java.net.HttpURLConnection;
import java.util.Objects;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.auth.HonoUser;
import org.eclipse.hono.config.ServiceConfigProperties;
import org.eclipse.hono.service.amqp.RequestResponseEndpoint;
import org.eclipse.hono.util.EventBusMessage;
import org.eclipse.hono.util.RegistrationConstants;
import org.eclipse.hono.util.ResourceIdentifier;
import org.springframework.beans.factory.annotation.Autowired;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * An {@code AmqpEndpoint} for managing device registration information.
 * <p>
 * This endpoint implements Hono's <a href="https://www.eclipse.org/hono/api/Device-Registration-API/">Device Registration API</a>.
 * It receives AMQP 1.0 messages representing requests and sends them to an address on the vertx
 * event bus for processing. The outcome is then returned to the peer in a response message.
 */
public class RegistrationAmqpEndpoint extends RequestResponseEndpoint<ServiceConfigProperties> {

    /**
     * Creates a new registration endpoint for a vertx instance.
     * 
     * @param vertx The vertx instance to use.
     */
    @Autowired
    public RegistrationAmqpEndpoint(final Vertx vertx) {
        super(Objects.requireNonNull(vertx));
    }

    @Override
    public final String getName() {
        return RegistrationConstants.REGISTRATION_ENDPOINT;
    }

    @Override
    public final void processRequest(final Message msg, final ResourceIdentifier targetAddress, final HonoUser clientPrincipal) {

        final JsonObject registrationMsg = EventBusMessage.forOperation(msg)
                .setTenant(targetAddress.getTenantId())
                .setDeviceId(msg)
                .setGatewayId(msg)
                .setJsonPayload(msg)
                .toJson();

        vertx.eventBus().send(RegistrationConstants.EVENT_BUS_ADDRESS_REGISTRATION_IN, registrationMsg,
                result -> {
                    EventBusMessage response = null;
                    if (result.succeeded()) {
                        // TODO check for correct session here...?
                        response = EventBusMessage.fromJson((JsonObject) result.result().body());
                    } else {
                        logger.debug("failed to process request [msg ID: {}] due to {}", msg.getMessageId(), result.cause());
                        // we need to inform client about failure
                        response = EventBusMessage.forStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR)
                                .setTenant(targetAddress.getTenantId())
                                .setDeviceId(msg);
                    }
                    addHeadersToResponse(msg, response);
                    vertx.eventBus().send(msg.getReplyTo(), response.toJson());
                });
    }

    @Override
    protected boolean passesFormalVerification(ResourceIdentifier linkTarget, Message msg) {
        return RegistrationMessageFilter.verify(linkTarget, msg);
    }

    @Override
    protected final Message getAmqpReply(io.vertx.core.eventbus.Message<JsonObject> message) {
        return RegistrationConstants.getAmqpReply(RegistrationConstants.REGISTRATION_ENDPOINT, message.body());
    }
}
