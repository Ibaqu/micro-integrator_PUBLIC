/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.micro.integrator.core.internal;

import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.clustering.ClusteringConstants;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.ConfigurationContextFactory;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.TransportInDescription;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.engine.ListenerManager;
import org.apache.axis2.transport.base.threads.ThreadCleanupContainer;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.commons.collections.BidiMap;
import org.apache.commons.collections.bidimap.TreeBidiMap;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.core.runtime.adaptor.EclipseStarter;
import org.eclipse.equinox.http.helper.FilterServletAdaptor;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.base.CarbonBaseConstants;
import org.wso2.micro.application.deployer.AppDeployerConstants;
import org.wso2.micro.application.deployer.AppDeployerUtils;
import org.wso2.micro.application.deployer.Feature;
import org.wso2.micro.application.deployer.handler.AppDeploymentHandler;
import org.wso2.micro.core.CarbonAxisConfigurator;
import org.wso2.micro.core.CarbonConfigurationContextFactory;
import org.wso2.micro.core.CarbonThreadCleanup;
import org.wso2.micro.core.CarbonThreadFactory;
import org.wso2.micro.core.ServerInitializer;
import org.wso2.micro.core.ServerManagement;
import org.wso2.micro.core.ServerStatus;
import org.wso2.micro.core.init.PreAxis2ConfigItemListener;
import org.wso2.micro.core.internal.HTTPGetProcessorListener;
import org.wso2.micro.core.multitenancy.GenericArtifactUnloader;
import org.wso2.micro.core.transports.CarbonServlet;
import org.wso2.micro.core.util.Axis2ConfigItemHolder;
import org.wso2.micro.core.util.HouseKeepingTask;
import org.wso2.micro.core.util.NetworkUtils;
import org.wso2.micro.core.util.ParameterUtil;
import org.wso2.micro.core.util.ServerException;
import org.wso2.micro.core.util.Utils;
import org.wso2.micro.integrator.core.services.Axis2ConfigurationContextService;
import org.wso2.micro.integrator.core.services.CarbonServerConfigurationService;
import org.wso2.micro.integrator.core.util.MicroIntegratorBaseUtils;

import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementPermission;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.servlet.Filter;
import javax.servlet.ServletException;

import static org.apache.axis2.transport.TransportListener.HOST_ADDRESS;

//import org.wso2.carbon.context.PrivilegedCarbonContext;

//import org.wso2.carbon.core.CarbonAxisConfigurator;

public class CoreServerInitializer {

    private static Log log = LogFactory.getLog(CoreServerInitializer.class);

    private static final String CLIENT_REPOSITORY_LOCATION = "Axis2Config.ClientRepositoryLocation";
    private static final String CLIENT_AXIS2_XML_LOCATION = "Axis2Config.clientAxis2XmlLocation";
    private static final String SERVICE_PATH = "service-path";
    private static final String BUNDLE_CONTEXT_ROOT = "bundleContext-root";
    private static final String HOST_NAME = "host-name";
    private static final ScheduledExecutorService artifactsCleanupExec = Executors
            .newScheduledThreadPool(1, new CarbonThreadFactory(new ThreadGroup("ArtifactCleanupThread")));

    protected CarbonServerConfigurationService serverConfigurationService;
    private BundleContext bundleContext;
    //  private static ApplicationManagerService applicationManager;
    private static Map<String, List<Feature>> requiredFeatures;

    private boolean isEmbedEnv = false;
    private String serverWorkDir;
    private String axis2RepoLocation;
    private String serverName;
    private org.wso2.micro.core.init.PreAxis2ConfigItemListener configItemListener;
    private Timer timer = new Timer();
    private org.wso2.micro.core.multitenancy.GenericArtifactUnloader genericArtifactUnloader = new GenericArtifactUnloader();
    private Thread shutdownHook;
    private ConfigurationContext serverConfigContext;
    private List<AppDeploymentHandler> appHandlers = new ArrayList<AppDeploymentHandler>();
    private List<String> requiredServices = new ArrayList<String>();
    /**
     * Indicates whether the shutdown of the server was triggered by the Carbon shutdown hook
     */
    private volatile boolean isShutdownTriggeredByShutdownHook = false;

    public CoreServerInitializer(CarbonServerConfigurationService serverConfigurationService, BundleContext bundleContext) {

        this.serverConfigurationService = serverConfigurationService;
        this.bundleContext = bundleContext;
    }

    public BundleContext getBundleContext() {
        return bundleContext;
    }

    public static Map<String, List<Feature>> getRequiredFeatures() {
        return requiredFeatures;
    }


    protected void initMIServer() {
        if (log.isDebugEnabled()) {
            log.debug(CoreServerInitializer.class.getName() + "#initMIServer() BEGIN - " + System.currentTimeMillis());
        }
        try {
            // for new caching, every thread should has its own populated CC. During the deployment time we assume super tenant
//            bundleContext.registerService(ServerStartupObserver.class.getName(), new DeploymentServerStartupObserver(),
//                                     null);

            ApplicationManager applicationManager = ApplicationManager.getInstance();
            applicationManager.init(); // this will allow application manager to register deployment handlers

            // read required-features.xml
            URL reqFeaturesResource = bundleContext.getBundle().getResource(AppDeployerConstants.REQ_FEATURES_XML);
            if (reqFeaturesResource != null) {
                InputStream xmlStream = reqFeaturesResource.openStream();
                requiredFeatures = AppDeployerUtils
                        .readRequiredFeaturs(new StAXOMBuilder(xmlStream).getDocumentElement());
            }
            // CarbonCoreDataHolder.getInstance().setBundleContext(ctxt.getBundleContext());
            //Initializing ConfigItem Listener - Modules and Deployers
            configItemListener = new PreAxis2ConfigItemListener(bundleContext);
            initializeCarbon();
        } catch (Throwable e) {
            log.error("Failed to activate Carbon Core bundle ", e);
        }
        if (log.isDebugEnabled()) {
            log.debug(CoreServerInitializer.class.getName() + "#initMIServer() COMPLETED - " + System.currentTimeMillis());
        }
    }

    private void initializeCarbon() {

        //        PrivilegedCarbonContext privilegedCarbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
        //        privilegedCarbonContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        //        privilegedCarbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

        // Reset the SAAJ Interfaces
        System.getProperties().remove("javax.xml.soap.MessageFactory");
        System.getProperties().remove("javax.xml.soap.SOAPConnectionFactory");

        try {
            if (log.isDebugEnabled()) {
                log.debug("Starting Carbon initialization...");
            }

            ThreadCleanupContainer.addThreadCleanup(new CarbonThreadCleanup());

            // Location for expanding web content within AAR files
            String webLocation = System.getProperty(CarbonConstants.WEB_RESOURCE_LOCATION);
            if (webLocation == null) {
                webLocation = System.getProperty("carbon.home") + File.separator + "repository" + File.separator
                        + "deployment" + File.separator + "server" + File.separator + "webapps" + File.separator
                        + "wservices";
            }

            String temp = System.getProperty(MicroIntegratorBaseConstants.STANDALONE_MODE);
            if (temp != null) {
                temp = temp.trim();
                isEmbedEnv = temp.equals("true");
            } else {
                isEmbedEnv = false;
            }

            //Checking Carbon home
            String carbonHome = System.getProperty(MicroIntegratorBaseConstants.CARBON_HOME);
            if (carbonHome == null) {
                String msg = MicroIntegratorBaseConstants.CARBON_HOME + "System property has not been set.";
                log.fatal(msg);
                log.fatal(serverName + " startup failed.");
                throw new ServletException(msg);
            }

            try {
                System.setProperty(MicroIntegratorBaseConstants.LOCAL_IP_ADDRESS, NetworkUtils.getLocalHostname());
            } catch (SocketException ignored) {
            }
            /* we create the serverconfiguration in the carbon base. There we don't know the local.ip property.
            hence we are setting it manually here */
            //TODO: proper fix would be to move the networkUtil class to carbon.base level
            String serverURL = serverConfigurationService.getFirstProperty(CarbonConstants.SERVER_URL);
            serverURL = Utils.replaceSystemProperty(serverURL);
            serverConfigurationService.overrideConfigurationProperty(CarbonConstants.SERVER_URL, serverURL);
            serverName = serverConfigurationService.getFirstProperty("Name");

            String hostName = serverConfigurationService.getFirstProperty("ClusteringHostName");
            if (System.getProperty(ClusteringConstants.LOCAL_IP_ADDRESS) == null && hostName != null
                    && hostName.trim().length() != 0) {
                System.setProperty(ClusteringConstants.LOCAL_IP_ADDRESS, hostName);
            }

            // Set the JGroups bind address for the use of the Caching Implementation based on
            // Infinispan.
            if (System.getProperty("bind.address") == null) {
                System.setProperty("bind.address", (hostName != null && hostName.trim().length() != 0) ?
                        hostName.trim() :
                        NetworkUtils.getLocalHostname());
            }

            serverWorkDir = new File(serverConfigurationService.getFirstProperty("WorkDirectory")).getAbsolutePath();
            System.setProperty("axis2.work.dir", serverWorkDir);

            setAxis2RepoLocation();

            Axis2ConfigItemHolder configItemHolder = new Axis2ConfigItemHolder();
            //TODO need to write deploy bundles
            configItemHolder.setDeployerBundles(configItemListener.getDeployerBundles());
            configItemHolder.setModuleBundles(configItemListener.getModuleBundles());

            String carbonContextRoot = serverConfigurationService.getFirstProperty("WebContextRoot");

            CarbonAxisConfigurator carbonAxisConfigurator = new CarbonAxisConfigurator();
            carbonAxisConfigurator.setAxis2ConfigItemHolder(configItemHolder);
            carbonAxisConfigurator.setBundleContext(bundleContext);
            carbonAxisConfigurator.setCarbonContextRoot(carbonContextRoot);
            if (!carbonAxisConfigurator.isInitialized()) {
                carbonAxisConfigurator.init(axis2RepoLocation, webLocation);
            }

            //This is the point where we initialize Axis2.
            long start = System.currentTimeMillis();
            if (log.isDebugEnabled()) {
                log.debug("Creating super-tenant Axis2 ConfigurationContext");
            }
            serverConfigContext = CarbonConfigurationContextFactory.
                    createNewConfigurationContext(carbonAxisConfigurator, bundleContext);
            long end = System.currentTimeMillis();
            if (log.isDebugEnabled()) {
                log.debug("Completed super-tenant Axis2 ConfigurationContext creation in " + ((double) (end - start)
                        / 1000) + " sec");
            }

            // Initialize ListenerManager
            ListenerManager listenerManager = serverConfigContext.getListenerManager();
            if (listenerManager == null) {
                listenerManager = new ListenerManager();
                listenerManager.init(serverConfigContext);
            }

            // Register listener manager. This is to break cyclic dependency between TasksDSComponent,
            // AppDeployerServiceComponent, StartupFinalizerServiceComponent
            bundleContext.registerService(ListenerManager.class.getName(), listenerManager, null);

            // At this point all the services and modules are deployed
            // Therefore it is time to allows other deployers to be registered.
            carbonAxisConfigurator.addAxis2ConfigServiceListener();

            initNetworkUtils(serverConfigContext.getAxisConfiguration());

            // Enabling http binding generation
            Parameter enableHttp = new Parameter("enableHTTP", "true");
            AxisConfiguration axisConfig = serverConfigContext.getAxisConfiguration();
            axisConfig.addParameter(enableHttp);

/*            new TransportPersistenceManager(axisConfig).
                    updateEnabledTransports(axisConfig.getTransportsIn().values(),
                            axisConfig.getTransportsOut().values());*/

            runInitializers();

            serverConfigContext.setProperty(Constants.CONTAINER_MANAGED, "true");
            serverConfigContext.setProperty(MicroIntegratorBaseConstants.WORK_DIR, serverWorkDir);
            // This is used inside the sever-admin component.
            serverConfigContext.setProperty(MicroIntegratorBaseConstants.CARBON_INSTANCE, this);

            //TODO As a tempory solution this part is added here. But when ui bundle are seperated from the core bundles
            //TODO this should be fixed.
            String type = serverConfigurationService.getFirstProperty("Security.TrustStore.Type");
            String password = serverConfigurationService.getFirstProperty("Security.TrustStore.Password");
            String storeFile = new File(serverConfigurationService.getFirstProperty("Security.TrustStore.Location")).getAbsolutePath();

            System.setProperty("javax.net.ssl.trustStore", storeFile);
            System.setProperty("javax.net.ssl.trustStoreType", type);
            System.setProperty("javax.net.ssl.trustStorePassword", password);

            addShutdownHook();
            //            registerHouseKeepingTask(serverConfigContext);

            // Creating the Client side configuration context
            ConfigurationContext clientConfigContext = getClientConfigurationContext();

            //TOa house keeping taskDO add this map to a house keeping task
            //Adding FILE_RESOURCE_MAP
            Object property = new TreeBidiMap();
            clientConfigContext.setProperty(MicroIntegratorBaseConstants.FILE_RESOURCE_MAP, property);
            clientConfigContext.setContextRoot(carbonContextRoot);

            if (log.isDebugEnabled()) {
                log.debug("Repository       : " + axis2RepoLocation);
            }

            // schedule the services cleanup task
           /* if (GhostDeployerUtils.isGhostOn()) {
                artifactsCleanupExec.scheduleAtFixedRate(genericArtifactUnloader,
                        CarbonConstants.SERVICE_CLEANUP_PERIOD_SECS,
                        CarbonConstants.SERVICE_CLEANUP_PERIOD_SECS, TimeUnit.SECONDS);
            }*/

            //Exposing metering.enabled system property. This is needed by the
            //tomcat.patch bundle to decide whether or not to publish bandwidth stat data
            String isMeteringEnabledStr = serverConfigurationService.getFirstProperty("EnableMetering");
            if (isMeteringEnabledStr != null) {
                System.setProperty("metering.enabled", isMeteringEnabledStr);
            } else {
                System.setProperty("metering.enabled", "false");
            }

            //Registering the configuration contexts as an OSGi service.
            if (log.isDebugEnabled()) {
                log.debug("Registering Axis2ConfigurationContextService...");
            }
            Axis2ConfigurationContextService axis2ConfigurationContextService =
                    new Axis2ConfigurationContextService(serverConfigContext, clientConfigContext);
            //Register Axis2ConfigurationContextService
            bundleContext.registerService(Axis2ConfigurationContextService.class.getName(),
                    axis2ConfigurationContextService, null);
            CarbonCoreDataHolder.getInstance().setAxis2ConfigurationContextService(axis2ConfigurationContextService);

        } catch (Throwable e) {
            log.fatal("WSO2 Carbon initialization Failed", e);
        }
    }

    private void addShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread() {
            public void run() {
                // During shutdown we assume it is triggered by super tenant
                //                PrivilegedCarbonContext privilegedCarbonContext = PrivilegedCarbonContext
                //                        .getThreadLocalCarbonContext();
                //                privilegedCarbonContext
                //                        .setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                //                privilegedCarbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

                log.debug("Shutdown hook triggered....");
                isShutdownTriggeredByShutdownHook = true;
                shutdownGracefully();
            }
        };
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Forced shutdown
     */
    public void shutdown() {
        SecurityManager secMan = System.getSecurityManager();
        if (secMan != null) {
            secMan.checkPermission(new ManagementPermission("control"));
        }
        if (log.isDebugEnabled()) {
            log.debug("Shutting down " + serverName + "...");
        }
        if (!isShutdownTriggeredByShutdownHook) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        try {
            try {
                ServerStatus.setServerShuttingDown();
            } catch (AxisFault e) {
                String msg = "Cannot set server to shutdown mode";
                log.error(msg, e);
            }
            //            CarbonCoreServiceComponent.shutdown();
            //            stopListenerManager();
            log.debug("Shutting down OSGi framework...");
            EclipseStarter.shutdown();
            log.info("Micro Integrator Shutdown complete");
            log.debug("Halting JVM");
            if (!isShutdownTriggeredByShutdownHook) {
                System.exit(0);
            }
        } catch (Exception e) {
            log.error("Error occurred while shutting down " + serverName, e);
            if (!isShutdownTriggeredByShutdownHook) {
                System.exit(1);
            }
        }
    }

    /**
     * Graceful shutdown
     */
    public void shutdownGracefully() {

        try {
            ServerStatus.setServerShuttingDown();
        } catch (Exception e) {
            String msg = "Cannot set server to shutdown mode";
            log.error(msg, e);
        }
        try {
            if (log.isDebugEnabled()) {
                log.debug("Gracefully shutting down " + serverName + "...");
            }
            Map<String, TransportInDescription> inTransports = serverConfigContext.getAxisConfiguration()
                    .getTransportsIn();
            new ServerManagement(inTransports, serverConfigContext).startMaintenanceForShutDown();
        } catch (Exception e) {
            String msg = "Cannot put transports into maintenance mode";
            log.error(msg, e);
        }
        shutdown();
    }

    private ConfigurationContext getClientConfigurationContext() throws AxisFault {
        String clientRepositoryLocation = serverConfigurationService.getFirstProperty(CLIENT_REPOSITORY_LOCATION);
        String clientAxis2XmlLocationn = serverConfigurationService.getFirstProperty(CLIENT_AXIS2_XML_LOCATION);
        ConfigurationContext clientConfigContextToReturn = ConfigurationContextFactory
                .createConfigurationContextFromFileSystem(clientRepositoryLocation, clientAxis2XmlLocationn);
        MultiThreadedHttpConnectionManager httpConnectionManager = new MultiThreadedHttpConnectionManager();
        HttpConnectionManagerParams params = new HttpConnectionManagerParams();

        // Set the default max connections per host
        int defaultMaxConnPerHost = 500;
        Parameter defaultMaxConnPerHostParam = clientConfigContextToReturn.getAxisConfiguration()
                .getParameter("defaultMaxConnPerHost");
        if (defaultMaxConnPerHostParam != null) {
            defaultMaxConnPerHost = Integer.parseInt((String) defaultMaxConnPerHostParam.getValue());
        }
        params.setDefaultMaxConnectionsPerHost(defaultMaxConnPerHost);

        // Set the max total connections
        int maxTotalConnections = 15000;
        Parameter maxTotalConnectionsParam = clientConfigContextToReturn.getAxisConfiguration()
                .getParameter("maxTotalConnections");
        if (maxTotalConnectionsParam != null) {
            maxTotalConnections = Integer.parseInt((String) maxTotalConnectionsParam.getValue());
        }
        params.setMaxTotalConnections(maxTotalConnections);

        params.setSoTimeout(600000);
        params.setConnectionTimeout(600000);

        httpConnectionManager.setParams(params);
        clientConfigContextToReturn
                .setProperty(HTTPConstants.MULTITHREAD_HTTP_CONNECTION_MANAGER, httpConnectionManager);
        //        registerHouseKeepingTask(clientConfigContextToReturn);
        clientConfigContextToReturn.setProperty(MicroIntegratorBaseConstants.WORK_DIR, serverWorkDir);
        return clientConfigContextToReturn;
    }

    private void registerHouseKeepingTask(ConfigurationContext configurationContext) {
        if (Boolean.valueOf(serverConfigurationService.getFirstProperty("HouseKeeping.AutoStart"))) {
            Timer houseKeepingTimer = new Timer();
            long houseKeepingInterval = Long.parseLong(serverConfigurationService.
                    getFirstProperty("HouseKeeping.Interval")) * 60 * 1000;
            Object property = configurationContext.getProperty(MicroIntegratorBaseConstants.FILE_RESOURCE_MAP);
            if (property == null) {
                property = new TreeBidiMap();
                configurationContext.setProperty(MicroIntegratorBaseConstants.FILE_RESOURCE_MAP, property);
            }
            houseKeepingTimer.
                    scheduleAtFixedRate(new HouseKeepingTask(serverWorkDir, (BidiMap) property), houseKeepingInterval,
                                        houseKeepingInterval);
        }
    }

    private void runInitializers() throws ServerException {

        String[] initializers = serverConfigurationService.getProperties("ServerInitializers.Initializer");
        for (String clazzName : initializers) {
            try {
                Class clazz = bundleContext.getBundle().loadClass(clazzName);
                ServerInitializer intializer = (ServerInitializer) clazz.newInstance();
                if (log.isDebugEnabled()) {
                    log.debug("Using ServerInitializer " + intializer.getClass().getName());
                }
                intializer.init(serverConfigContext);
            } catch (Exception e) {
                throw new ServerException(e);
            }
        }
    }

    private void setAxis2RepoLocation() {
        if (System.getProperty("axis2.repo") != null) {
            axis2RepoLocation = System.getProperty("axis2.repo");
            /* First see whether this is the -n scenario */
            if (MicroIntegratorBaseUtils.isMultipleInstanceCase()) {
                /* Now check whether this is a ChildNode or not, if this is the
                   a ChildNode we do not deploy services to this */
                if (!MicroIntegratorBaseUtils.isChildNode()) {
                    axis2RepoLocation = MicroIntegratorBaseUtils.getCarbonHome();
                }
            }
            serverConfigurationService.setConfigurationProperty(CarbonBaseConstants.AXIS2_CONFIG_REPO_LOCATION, axis2RepoLocation);
        } else {
            axis2RepoLocation = serverConfigurationService.getFirstProperty(CarbonBaseConstants.AXIS2_CONFIG_REPO_LOCATION);
        }

        if (!axis2RepoLocation.endsWith("/")) {
            serverConfigurationService
                    .setConfigurationProperty(CarbonBaseConstants.AXIS2_CONFIG_REPO_LOCATION, axis2RepoLocation + "/");
            axis2RepoLocation = axis2RepoLocation + "/";
        }
    }

    private void registerCarbonServlet(HttpService httpService, HttpContext defaultHttpContext)
            throws ServletException, NamespaceException, InvalidSyntaxException {
        if (!"false".equals(serverConfigurationService.getFirstProperty("RequireCarbonServlet"))) {
            org.wso2.micro.core.transports.CarbonServlet carbonServlet = new CarbonServlet(serverConfigContext);
            String servicePath = "/services";
            String path = serverConfigContext.getServicePath();
            if (path != null) {
                servicePath = path.trim();
            }
            if (!servicePath.startsWith("/")) {
                servicePath = "/" + servicePath;
            }
            ServiceReference filterServiceReference = bundleContext.getServiceReference(Filter.class.getName());
            if (filterServiceReference != null) {
                Filter filter = (Filter) bundleContext.getService(filterServiceReference);
                httpService.registerServlet(servicePath, new FilterServletAdaptor(filter, null, carbonServlet), null,
                                            defaultHttpContext);
            } else {
                httpService.registerServlet(servicePath, carbonServlet, null, defaultHttpContext);
            }
            org.wso2.micro.core.internal.HTTPGetProcessorListener getProcessorListener = new HTTPGetProcessorListener(
                    carbonServlet, bundleContext);
            // Check whether there are any services that expose HTTPGetRequestProcessors
            ServiceReference[] getRequestProcessors = bundleContext.getServiceReferences((String) null, "("
                    + CarbonConstants.HTTP_GET_REQUEST_PROCESSOR_SERVICE + "=*)");

            // If there are any we need to register them explicitly
            if (getRequestProcessors != null) {
                for (ServiceReference getRequestProcessor : getRequestProcessors) {
                    getProcessorListener.addHTTPGetRequestProcessor(getRequestProcessor, ServiceEvent.REGISTERED);
                }
            }

            // We also add a service listener to make sure we react to changes in the bundles that
            // expose HTTPGetRequestProcessors
            bundleContext.addServiceListener(getProcessorListener,
                                             "(" + CarbonConstants.HTTP_GET_REQUEST_PROCESSOR_SERVICE + "=*)");
        }
    }

    private void initNetworkUtils(AxisConfiguration axisConfiguration) throws AxisFault, SocketException {
        String hostName = serverConfigurationService.getFirstProperty("HostName");
        String mgtHostName = serverConfigurationService.getFirstProperty("MgtHostName");
        if (hostName != null) {
            Parameter param = axisConfiguration.getParameter(HOST_ADDRESS);
            if (param != null) {
                param.setEditable(true);
                param.setValue(hostName);
            } else {
                param = ParameterUtil.createParameter(HOST_ADDRESS, hostName);
                axisConfiguration.addParameter(param);
            }
        } else {
            Parameter param = axisConfiguration.getParameter(HOST_ADDRESS);
            if (param != null) {
                hostName = (String) param.getValue();
                log.info(HOST_ADDRESS + " has been selected from Axis2.xml.");
            }
        }
        NetworkUtils.init(hostName, mgtHostName);
    }
}
