/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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


package org.wso2.micro.integrator.management.apis;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.rest.cors.CORSConfiguration;
import org.wso2.micro.integrator.inbound.endpoint.internal.http.api.APIResource;
import org.wso2.micro.integrator.inbound.endpoint.internal.http.api.InternalAPI;
import org.wso2.micro.integrator.inbound.endpoint.internal.http.api.InternalAPICORSConfiguration;
import org.wso2.micro.integrator.inbound.endpoint.internal.http.api.InternalAPIHandler;

import java.util.ArrayList;
import java.util.List;

import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_APIS;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_CARBON_APPS;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_CONNECTORS;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_DATA_SERVICES;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_ENDPOINTS;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_INBOUND_ENDPOINTS;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_LOCAL_ENTRIES;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_LOGGING;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_MESSAGE_PROCESSORS;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_MESSAGE_STORE;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_PROXY_SERVICES;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_SEQUENCES;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_TASKS;
import static org.wso2.micro.integrator.management.apis.Constants.PREFIX_TEMPLATES;
import static org.wso2.micro.integrator.management.apis.Constants.REST_API_CONTEXT;

public class ManagementInternalApi implements InternalAPI {

    private String name;
    private static Log LOG = LogFactory.getLog(ManagementInternalApi.class);
    private APIResource[] resources;
    private List<InternalAPIHandler> handlerList = null;
    private CORSConfiguration apiCORSConfiguration = null;

    public ManagementInternalApi() {

        LOG.warn("Micro Integrator Management REST API is enabled");

        ArrayList<APIResource> resourcesList = new ArrayList<>();
        resourcesList.add(new ApiResource(PREFIX_APIS));
        resourcesList.add(new EndpointResource(PREFIX_ENDPOINTS));
        resourcesList.add(new InboundEndpointResource(PREFIX_INBOUND_ENDPOINTS));
        resourcesList.add(new ProxyServiceResource(PREFIX_PROXY_SERVICES));
        resourcesList.add(new CarbonAppResource(PREFIX_CARBON_APPS));
        resourcesList.add(new TaskResource(PREFIX_TASKS));
        resourcesList.add(new SequenceResource(PREFIX_SEQUENCES));
        resourcesList.add(new DataServiceResource(PREFIX_DATA_SERVICES));
        resourcesList.add(new TemplateResource(PREFIX_TEMPLATES));
        resourcesList.add(new LoggingResource(PREFIX_LOGGING));
        resourcesList.add(new ApiResourceAdapter(PREFIX_MESSAGE_STORE, new MessageStoreResource()));
        resourcesList.add(new MessageProcessorResource(PREFIX_MESSAGE_PROCESSORS));
        resourcesList.add(new ApiResourceAdapter(PREFIX_LOCAL_ENTRIES, new LocalEntryResource()));
        resourcesList.add(new ApiResourceAdapter(PREFIX_CONNECTORS, new ConnectorResource()));

        resources = new APIResource[resourcesList.size()];
        resources = resourcesList.toArray(resources);
    }

    public APIResource[] getResources() {
        return resources;
    }

    public String getContext() {
        return REST_API_CONTEXT;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public void setHandlers(List<InternalAPIHandler> handlerList) {
        this.handlerList = handlerList;
    }

    @Override
    public List<InternalAPIHandler> getHandlers() {
        return this.handlerList;
    }

    @Override
    public void setCORSConfiguration(CORSConfiguration corsConfiguration) {
        apiCORSConfiguration = corsConfiguration;
    }

    @Override
    public CORSConfiguration getCORSConfiguration() {
        return apiCORSConfiguration;
    }

}
