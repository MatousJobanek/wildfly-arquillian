/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.arquillian.container;

import static org.jboss.as.arquillian.container.Authentication.getCallbackHandler;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Properties;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.context.annotation.ContainerScoped;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.ApplicationScoped;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.xnio.IoUtils;

/**
 * A JBossAS deployable container
 *
 * @author Thomas.Diesler@jboss.com
 * @since 17-Nov-2010
 */
public abstract class CommonDeployableContainer<T extends CommonContainerConfiguration> implements DeployableContainer<T> {

    private static final String JBOSS_URL_PKG_PREFIX = "org.jboss.ejb.client.naming";

    private T containerConfig;

    @Inject
    @ContainerScoped
    private InstanceProducer<ManagementClient> managementClient;

    @Inject
    @ContainerScoped
    private InstanceProducer<ArchiveDeployer> archiveDeployer;

    @Inject
    @ApplicationScoped
    private InstanceProducer<Context> jndiContext;

    private ContainerDescription containerDescription = null;

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override
    public void setup(T config) {
        containerConfig = config;
    }

    @Override
    public final void start() throws LifecycleException {
        if(containerConfig.getUsername() != null) {
            Authentication.username = containerConfig.getUsername();
            Authentication.password = containerConfig.getPassword();
        }

        ModelControllerClient modelControllerClient = null;
        try {
            modelControllerClient = ModelControllerClient.Factory.create(
                    containerConfig.getManagementProtocol(),
                    containerConfig.getManagementAddress(),
                    containerConfig.getManagementPort(),
                    getCallbackHandler());
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }

        ManagementClient client = new ManagementClient(modelControllerClient, containerConfig.getManagementAddress(), containerConfig.getManagementPort(), containerConfig.getManagementProtocol());
        managementClient.set(client);

        ArchiveDeployer deployer = new ArchiveDeployer(client);
        archiveDeployer.set(deployer);

        try {
            final Properties jndiProps = new Properties();
            jndiProps.setProperty(Context.URL_PKG_PREFIXES, JBOSS_URL_PKG_PREFIX);
            jndiContext.set(new InitialContext(jndiProps));
        } catch (final NamingException ne) {
            throw new LifecycleException("Could not set JNDI Naming Context", ne);
        }

        try {
            startInternal();
        } catch (LifecycleException e) {
            safeCloseClient();
            throw e;
        }
    }

    protected abstract void startInternal() throws LifecycleException;

    @Override
    public final void stop() throws LifecycleException {
        try {
            stopInternal(null);
        } finally {
            safeCloseClient();
        }
    }

    public final void stop(Integer timeout) throws LifecycleException {
        try {
            stopInternal(timeout);
        } finally {
            safeCloseClient();
        }
    }

    protected abstract void stopInternal(Integer timeout) throws LifecycleException;

    /**
     * Returns a description for the running container. If the container has not been started {@code null} will be
     * returned.
     *
     * @return the description for the running container or {@code null} if the container has not yet been started
     */
    public ContainerDescription getContainerDescription() {
        if (containerDescription == null) {
            try {
                final ManagementClient client = getManagementClient();
                // The management client should be set when the container is started
                if (client == null) return null;
                containerDescription = StandardContainerDescription.lookup(client);
            } catch (IOException e) {
                Logger.getLogger(getClass()).warn("Failed to lookup the container description.", e);
                containerDescription = StandardContainerDescription.NULL_DESCRIPTION;
            }
        }
        return containerDescription;
    }

    protected T getContainerConfiguration() {
        return containerConfig;
    }

    protected ManagementClient getManagementClient() {
        return managementClient.get();
    }

    protected ModelControllerClient getModelControllerClient() {
        return getManagementClient().getControllerClient();
    }

    /**
     * Checks to see if the attribute is a valid attribute for the operation. This is useful to determine if the running
     * container supports an attribute for the version running.
     *
     * <p>
     * This is the same as executing {@link #isOperationAttributeSupported(ModelNode, String, String) isOperationAttriubuteSupported(null, operationName, attributeName)}
     * </p>
     *
     * @param operationName the operation name
     * @param attributeName the attribute name
     *
     * @return {@code true} if the attribute is supported or {@code false} if the attribute was not found on the
     * operation description
     *
     * @throws IOException           if an error occurs while attempting to execute the operation
     * @throws IllegalStateException if the operation fails
     */
    protected boolean isOperationAttributeSupported(final String operationName, final String attributeName) throws IOException {
        return isOperationAttributeSupported(null, operationName, attributeName);
    }

    /**
     * Checks to see if the attribute is a valid attribute for the operation. This is useful to determine if the running
     * container supports an attribute for the version running.
     *
     * @param address       the address or {@code null} for the root resource
     * @param operationName the operation name
     * @param attributeName the attribute name
     *
     * @return {@code true} if the attribute is supported or {@code false} if the attribute was not found on the
     * operation description
     *
     * @throws IOException           if an error occurs while attempting to execute the operation
     * @throws IllegalStateException if the operation fails
     */
    protected boolean isOperationAttributeSupported(final ModelNode address, final String operationName, final String attributeName) throws IOException {
        final ModelControllerClient client = getModelControllerClient();
        final ModelNode op;
        if (address == null) {
            op = Operations.createOperation(ModelDescriptionConstants.READ_OPERATION_DESCRIPTION_OPERATION);
        } else {
            op = Operations.createOperation(ModelDescriptionConstants.READ_OPERATION_DESCRIPTION_OPERATION, address);
        }
        op.get(ModelDescriptionConstants.NAME).set(operationName);
        final ModelNode result = client.execute(op);
        if (Operations.isSuccessfulOutcome(result)) {
            final ModelNode params = Operations.readResult(result).get(ModelDescriptionConstants.REQUEST_PROPERTIES);
            return params.keys().contains(attributeName);
        }
        final String msg;
        if (address == null) {
            msg = String.format("Failed to determine if attribute %s is supported for operation %s. %s", attributeName, operationName, Operations.getFailureDescription(result));
        } else {
            msg = String.format("Failed to determine if attribute %s is supported for operation %s:%s. %s", attributeName, addressToCliString(address), operationName, Operations.getFailureDescription(result));
        }
        throw new IllegalStateException(msg);
    }

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        String runtimeName = archiveDeployer.get().deploy(archive);
        return getManagementClient().getProtocolMetaData(runtimeName);
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        archiveDeployer.get().undeploy(archive.getName());
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException("not implemented");
    }

    private void safeCloseClient() {
        try {
            IoUtils.safeClose(getManagementClient());
        } catch (final Exception e) {
            Logger.getLogger(getClass()).warn("Caught exception closing ModelControllerClient", e);
        }
    }

    private static String addressToCliString(final ModelNode address) {
        if (address == null) {
            return "";
        }
        final StringBuilder result = new StringBuilder(32);
        for (Property property : address.asPropertyList()) {
            result.append('/').append(property.getName()).append('=').append(property.getValue().asString());
        }
        return result.toString();
    }
}
