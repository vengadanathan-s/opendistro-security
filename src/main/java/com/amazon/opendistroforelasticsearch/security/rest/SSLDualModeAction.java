/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazon.opendistroforelasticsearch.security.rest;

import com.amazon.opendistroforelasticsearch.security.ssl.transport.OpenDistroSSLConfig;
import com.amazon.opendistroforelasticsearch.security.support.ConfigConstants;
import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.PUT;

public class SSLDualModeAction extends BaseRestHandler {

    private static final Logger logger = LogManager.getLogger(SSLDualModeAction.class);
    private static final String RESPONSE_ENABLED_FIELD = "enabled";
    private static final String RESPONSE_ERROR_FIELD = "error";

    private ClusterSettings clusterSettings;
    private Settings settings;

    private static final Logger logger = LogManager.getLogger(SSLDualModeAction.class);

    private static final Logger logger = LogManager.getLogger(SSLDualModeAction.class);

    private static final List<Route> routes = ImmutableList.of(
            // gets the current status of ssl dual mode
            new Route(GET, "/_opendistro/_security/ssl_dual_mode")
    );

    private final Settings settings;

    public SSLDualModeAction(final Settings settings, final OpenDistroSSLConfig openDistroSSLConfig) {
        this.settings = settings;
        this.openDistroSSLConfig = openDistroSSLConfig;
    }

    @Override
    public String getName() {
        return "ssl_dual_mode";
    }

    @Override
    public List<Route> routes() {
        return routes;
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        return restChannel -> {
            boolean dualModeEnabled = false;
            if (openDistroSSLConfig.isDualModeEnabled()) {
                dualModeEnabled = true;
            }
            BytesRestResponse response = getDualModeResponse(restChannel, dualModeEnabled);
            restChannel.sendResponse(response);
        };
    }

    private BytesRestResponse getDualModeResponse(final RestChannel restChannel, final boolean enabled) {
        XContentBuilder builder;
        try {
            builder = restChannel.newBuilder();
            builder.startObject();
            builder.field(RESPONSE_ENABLED_FIELD, enabled);
            builder.endObject();
            builder.close();
        } catch (IOException e) {
            logger.error("Unable to generate response", e);
            throw new ElasticsearchException(e);
        }
        return new BytesRestResponse(RestStatus.OK, builder);
    }
}
